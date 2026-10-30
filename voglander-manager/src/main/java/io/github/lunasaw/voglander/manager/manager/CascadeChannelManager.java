package io.github.lunasaw.voglander.manager.manager;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.Assert;

import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.assembler.CascadeAssembler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.service.CascadeChannelService;
import io.github.lunasaw.voglander.repository.entity.CascadeChannelDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联上报通道管理器
 */
@Slf4j
@Component
public class CascadeChannelManager {

    @Autowired
    private CascadeChannelService cascadeChannelService;

    @Autowired
    private CascadeOrgManager     cascadeOrgManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public Long add(CascadeChannelDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getPlatformId(), "platformId 不能为空");
        Assert.hasText(dto.getLocalChannelId(), "localChannelId 不能为空");
        /* cascadeChannelId 默认同 localChannelId */
        if (dto.getCascadeChannelId() == null) {
            dto.setCascadeChannelId(dto.getLocalChannelId());
        }
        dto.setCascadeOrgId(normalizeOrgId(dto.getPlatformId(), dto.getCascadeOrgId()));
        CascadeChannelDO doObj = CascadeAssembler.toDO(dto);
        cascadeChannelService.save(doObj);
        return doObj.getId();
    }

    /**
     * 批量绑定级联通道：逐条 upsert，已存在（同 platformId+localChannelId）的跳过。
     *
     * @param channels 待绑定通道列表（platformId/localChannelId 必填，cascadeChannelId 缺省=localChannelId）
     * @return 实际新增条数（跳过已存在的不计）
     */
    public int batchBind(List<CascadeChannelDTO> channels) {
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (CascadeChannelDTO dto : channels) {
            if (dto == null || dto.getPlatformId() == null || dto.getLocalChannelId() == null) {
                continue;
            }
            CascadeChannelDTO existed = getByPlatformAndChannel(dto.getPlatformId(), dto.getLocalChannelId());
            if (existed != null) {
                continue;
            }
            add(dto);
            added++;
        }
        return added;
    }

    public CascadeChannelDTO getByPlatformAndChannel(String platformId, String localChannelId) {
        if (platformId == null || localChannelId == null) return null;
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeChannelDO::getPlatformId, platformId)
          .eq(CascadeChannelDO::getLocalChannelId, localChannelId);
        return CascadeAssembler.toDTO(cascadeChannelService.getOne(qw));
    }

    /** 按上级眼中的 cascadeChannelId 查找 */
    public CascadeChannelDTO getByPlatformAndCascadeChannelId(String platformId, String cascadeChannelId) {
        if (platformId == null || cascadeChannelId == null) return null;
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeChannelDO::getPlatformId, platformId)
          .eq(CascadeChannelDO::getCascadeChannelId, cascadeChannelId);
        return CascadeAssembler.toDTO(cascadeChannelService.getOne(qw));
    }

    public List<CascadeChannelDTO> listByPlatformId(String platformId) {
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(CascadeChannelDO::getPlatformId, platformId);
        return cascadeChannelService.list(qw).stream().map(CascadeAssembler::toDTO).toList();
    }

    public boolean deleteOne(CascadeChannelDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        /* 删前留底：删完查不到平台编码/通道编码，就发不出 DEL 通知 */
        CascadeChannelDTO existed = dto.getId() != null ? getById(dto.getId()) : null;
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(dto.getPlatformId() != null, CascadeChannelDO::getPlatformId, dto.getPlatformId())
          .eq(dto.getLocalChannelId() != null, CascadeChannelDO::getLocalChannelId, dto.getLocalChannelId())
          .eq(dto.getId() != null, CascadeChannelDO::getId, dto.getId());
        boolean ok = cascadeChannelService.remove(qw);
        if (ok) {
            publishDeleted(existed != null ? existed : dto);
        }
        return ok;
    }

    public CascadeChannelDTO getById(Long id) {
        if (id == null) return null;
        return CascadeAssembler.toDTO(cascadeChannelService.getById(id));
    }

    /**
     * 更新级联通道。
     *
     * <p>{@code cascadeOrgId} 三态语义：{@code null} 不动组织归属；空串迁回平台根节点；
     * 非空迁到该组织。空串之所以要单独处理，是因为 {@code updateById} 会跳过 null 字段，
     * 直接把 null 交给它等于"不改"，迁回根节点永远不生效。
     *
     * <p><b>启停不只是改字段</b>：{@code 1 -> 0} 必须显式推 {@code Event=DEL}，否则上级目录里的
     * 通道会永久留着（快照是增量 upsert 语义，不会因为节点「这次没出现」就删）；
     * {@code 0 -> 1} 同理需要 {@code ADD}，否则要等到下一次整树重推才回来。
     */
    public boolean update(CascadeChannelDTO dto) {
        Assert.notNull(dto.getId(), "id 不能为空");
        CascadeChannelDTO existed = getById(dto.getId());
        Assert.notNull(existed, "级联通道不存在: id=" + dto.getId());
        String  platformId   = existed.getPlatformId();
        boolean orgIdTouched = dto.getCascadeOrgId() != null;
        boolean orgChanged   = false;
        if (orgIdTouched) {
            String orgId = normalizeOrgId(platformId, dto.getCascadeOrgId());
            dto.setCascadeOrgId(orgId);
            orgChanged = !StringUtils.equals(existed.getCascadeOrgId(), orgId);
        }
        boolean wasEnabled = existed.getEnabled() == null || existed.getEnabled() == 1;
        boolean nowEnabled = dto.getEnabled() == null ? wasEnabled : dto.getEnabled() == 1;
        /* 0 -> 1 是「重新上报」：归属组织的整条祖先链都得在报，否则推上去又是一条孤儿；
           换组织时 normalizeOrgId 已经校验过目标组织，不必重复回表 */
        if (!wasEnabled && nowEnabled && !orgIdTouched) {
            assertOrgPublished(platformId, existed.getCascadeOrgId());
        }

        boolean ok = cascadeChannelService.updateById(CascadeAssembler.toDO(dto));
        if (orgIdTouched && dto.getCascadeOrgId() == null) {
            LambdaUpdateWrapper<CascadeChannelDO> uw = new LambdaUpdateWrapper<>();
            uw.eq(CascadeChannelDO::getId, dto.getId())
                .set(CascadeChannelDO::getCascadeOrgId, null);
            cascadeChannelService.update(uw);
        }
        if (!ok) {
            return false;
        }

        if (wasEnabled && !nowEnabled) {
            /* 删的是上级眼里那个节点，编码变过也要用改之前的 */
            publishEvent(platformId, existed.getCascadeChannelId(), "DEL");
        } else if (!wasEnabled && nowEnabled) {
            publishEvent(platformId,
                StringUtils.defaultIfBlank(dto.getCascadeChannelId(), existed.getCascadeChannelId()), "ADD");
        } else if (orgChanged) {
            /* 换组织改变的是父子关系，增量条目表达不了，由事件桥接重推整棵目录 */
            publishEvent(platformId, existed.getCascadeChannelId(), "UPDATE");
        }
        return true;
    }

    /**
     * 取消单条通道同步：不再上报，记录保留可恢复。
     *
     * @param id 通道映射主键
     * @return 是否变更（已处于停报状态时幂等返回 true）
     */
    public boolean revoke(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeChannelDTO existed = getById(id);
        Assert.notNull(existed, "级联通道不存在: id=" + id);
        if (existed.getEnabled() != null && existed.getEnabled() == 0) {
            return true;
        }
        CascadeChannelDO patch = new CascadeChannelDO();
        patch.setId(id);
        patch.setEnabled(0);
        boolean ok = cascadeChannelService.updateById(patch);
        if (ok) {
            log.info("取消级联通道同步: id={}, cascadeChannelId={}", id, existed.getCascadeChannelId());
            publishEvent(existed.getPlatformId(), existed.getCascadeChannelId(), "DEL");
        }
        return ok;
    }

    /**
     * 恢复单条通道同步。
     *
     * <p>所属组织（含整条祖先链）不在上报状态时拒绝：通道的 {@code ParentID} 指向一个上级看不到的
     * 组织，恢复后只会变成一条挂不上去的孤儿条目。只报错不自动恢复上级——一条开关就放出一整棵
     * 子树，用户对「上级目录一次多出什么」会失去预期。
     */
    public boolean publish(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeChannelDTO existed = getById(id);
        Assert.notNull(existed, "级联通道不存在: id=" + id);
        assertOrgPublished(existed.getPlatformId(), existed.getCascadeOrgId());
        CascadeChannelDO patch = new CascadeChannelDO();
        patch.setId(id);
        patch.setEnabled(1);
        boolean ok = cascadeChannelService.updateById(patch);
        if (ok) {
            log.info("恢复级联通道同步: id={}, cascadeChannelId={}", id, existed.getCascadeChannelId());
            publishEvent(existed.getPlatformId(), existed.getCascadeChannelId(), "ADD");
        }
        return ok;
    }

    /** 发布目录变更事件，由 {@code CascadeEventBridge} 转成 MANSCDP 报文 */
    private void publishEvent(String platformId, String cascadeChannelId, String event) {
        if (eventPublisher == null || StringUtils.isBlank(platformId)
            || StringUtils.isBlank(cascadeChannelId)) {
            log.warn("级联通道变更未通知上级，缺少平台/通道编码: platformId={}, cascadeChannelId={}, event={}",
                platformId, cascadeChannelId, event);
            return;
        }
        eventPublisher.publishEvent(
            new LocalCascadeCatalogChangeEvent(platformId, "CHANNEL", cascadeChannelId, event));
    }

    /**
     * 校验并规整组织的归属编码：空白归一为 null（挂平台根节点），非空必须是上级目录里真实可见的组织。
     *
     * <p>校验放在写入口而非出参组装：脏归属一旦落库，上级侧表现为「通道收得到但不挂在任何组织下」，
     * 或者被快照直接剪掉（组织取消同步且策略为 CASCADE）——页面显示上报中，上级却一条也看不到。
     */
    private String normalizeOrgId(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(cascadeOrgId)) {
            return null;
        }
        assertOrgPublished(platformId, cascadeOrgId);
        return cascadeOrgId;
    }

    /**
     * 校验通道归属的组织在上级目录里真实可见（自身与整条祖先链都在报）。
     *
     * <p>只看自身 {@code enabled} 不够：组织 A 停报后，子组织 B 的开关仍是 1，
     * 但 A、B 早已被 DEL 从上级目录删掉，此时把通道推到 B 下只会得到一条挂不上去的孤儿节点。
     */
    private void assertOrgPublished(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(cascadeOrgId)) {
            return;
        }
        CascadeOrgDTO org = cascadeOrgManager.getByPlatformAndCode(platformId, cascadeOrgId);
        Assert.notNull(org, "级联组织不存在: platformId=" + platformId + ", cascadeOrgId=" + cascadeOrgId);
        CascadeOrgDTO disabled = cascadeOrgManager.findDisabledInChain(platformId, cascadeOrgId);
        Assert.isNull(disabled, () -> {
            String name = StringUtils.defaultIfBlank(disabled.getOrgName(), disabled.getCascadeOrgId());
            /* 停在自己身上和停在上级，用户的下一步动作不一样，报错里分开说 */
            String hint = Objects.equals(disabled.getId(), org.getId())
                ? "" : "（该组织本身是上报的，被它的上级连带）";
            return "级联组织【" + name + "】处于停报状态，请先恢复它" + hint;
        });
    }

    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeChannelDTO existed = getById(id);
        boolean ok = cascadeChannelService.removeById(id);
        if (ok) {
            publishDeleted(existed);
        }
        return ok;
    }

    /**
     * 通道删除 → 通知上级。
     *
     * <p>不发这条事件，上级目录里的通道会永久留着：完整快照是增量 upsert 语义，
     * 上级不会因为节点「这次没出现」而删除，必须显式推 {@code Event=DEL}。
     */
    private void publishDeleted(CascadeChannelDTO existed) {
        if (existed == null || StringUtils.isBlank(existed.getPlatformId())
            || StringUtils.isBlank(existed.getCascadeChannelId())) {
            log.warn("级联通道已删除，但缺少平台/通道编码，无法通知上级删除");
            return;
        }
        eventPublisher.publishEvent(
            new LocalCascadeCatalogChangeEvent(existed.getPlatformId(), "CHANNEL",
                existed.getCascadeChannelId(), "DEL"));
    }

    public Page<CascadeChannelDTO> getPage(CascadeChannelDTO query, int page, int size) {
        return getPage(query, page, size, false);
    }

    /**
     * 分页查询级联通道。
     *
     * <p>{@code includeSubOrg} 决定 {@code cascadeOrgId} 的语义：{@code false} 只取
     * 直属该组织的通道（精确匹配，升级前行为）；{@code true} 取整棵子树下的通道，
     * 靠 {@code CascadeOrgManager#listSubtreeCodes} 先拿组织编码集合再 {@code IN}。
     * 组织层数再多也不需要递归 SQL——子树编码集合本身是一条前缀匹配查询拿到的。
     *
     * @param includeSubOrg 是否把子组织下的通道一并纳入
     */
    public Page<CascadeChannelDTO> getPage(CascadeChannelDTO query, int page, int size, boolean includeSubOrg) {
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (includeSubOrg && StringUtils.isNotBlank(query.getCascadeOrgId())) {
                String platformId = query.getPlatformId();
                if (StringUtils.isBlank(platformId)) {
                    /* 编码只在平台内唯一，缺平台就无法确定子树，退化为直属匹配而不是猜 */
                    log.warn("按子树过滤通道时缺 platformId，退化为直属匹配: cascadeOrgId={}", query.getCascadeOrgId());
                    qw.eq(CascadeChannelDO::getCascadeOrgId, query.getCascadeOrgId());
                } else {
                    List<String> subtreeCodes = cascadeOrgManager.listSubtreeCodes(platformId, query.getCascadeOrgId(), true);
                    if (subtreeCodes.isEmpty()) {
                        /* 组织不存在或已删除：返回空页，绝不能退化成"不过滤"把全平台通道吐出去 */
                        return new Page<>(page, size, 0);
                    }
                    qw.in(CascadeChannelDO::getCascadeOrgId, subtreeCodes);
                }
            } else {
                qw.eq(query.getCascadeOrgId() != null, CascadeChannelDO::getCascadeOrgId, query.getCascadeOrgId());
            }
            qw.eq(query.getPlatformId() != null, CascadeChannelDO::getPlatformId, query.getPlatformId());
            qw.eq(query.getLocalDeviceId() != null, CascadeChannelDO::getLocalDeviceId, query.getLocalDeviceId());
            qw.eq(query.getLocalChannelId() != null, CascadeChannelDO::getLocalChannelId, query.getLocalChannelId());
            qw.eq(query.getCascadeChannelId() != null, CascadeChannelDO::getCascadeChannelId, query.getCascadeChannelId());
        }
        qw.orderByDesc(CascadeChannelDO::getCreateTime);
        Page<CascadeChannelDO> doPage = cascadeChannelService.page(new Page<>(page, size), qw);
        Page<CascadeChannelDTO> result = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        result.setRecords(doPage.getRecords().stream().map(CascadeAssembler::toDTO).collect(java.util.stream.Collectors.toList()));
        return result;
    }
}
