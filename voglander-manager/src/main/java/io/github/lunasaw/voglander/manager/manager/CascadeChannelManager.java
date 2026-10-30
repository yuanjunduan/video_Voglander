package io.github.lunasaw.voglander.manager.manager;

import java.util.List;

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
        LambdaQueryWrapper<CascadeChannelDO> qw = new LambdaQueryWrapper<>();
        qw.eq(dto.getPlatformId() != null, CascadeChannelDO::getPlatformId, dto.getPlatformId())
          .eq(dto.getLocalChannelId() != null, CascadeChannelDO::getLocalChannelId, dto.getLocalChannelId())
          .eq(dto.getId() != null, CascadeChannelDO::getId, dto.getId());
        return cascadeChannelService.remove(qw);
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
     */
    public boolean update(CascadeChannelDTO dto) {
        Assert.notNull(dto.getId(), "id 不能为空");
        boolean orgIdTouched = dto.getCascadeOrgId() != null;
        String  orgId        = null;
        boolean orgChanged   = false;
        String  platformId   = null;
        if (orgIdTouched) {
            CascadeChannelDTO existed = getById(dto.getId());
            Assert.notNull(existed, "级联通道不存在: id=" + dto.getId());
            platformId = existed.getPlatformId();
            orgId = normalizeOrgId(platformId, dto.getCascadeOrgId());
            dto.setCascadeOrgId(orgId);
            orgChanged = !StringUtils.equals(existed.getCascadeOrgId(), orgId);
        }

        boolean ok = cascadeChannelService.updateById(CascadeAssembler.toDO(dto));
        if (orgIdTouched && orgId == null) {
            LambdaUpdateWrapper<CascadeChannelDO> uw = new LambdaUpdateWrapper<>();
            uw.eq(CascadeChannelDO::getId, dto.getId())
                .set(CascadeChannelDO::getCascadeOrgId, null);
            cascadeChannelService.update(uw);
        }
        if (ok && orgChanged) {
            /* 换组织改变的是父子关系，增量条目表达不了，由事件桥接重推整棵目录 */
            eventPublisher.publishEvent(
                new LocalCascadeCatalogChangeEvent(platformId, "CHANNEL", dto.getCascadeChannelId(), "UPDATE"));
        }
        return ok;
    }

    /**
     * 校验并规整组织的归属编码：空白归一为 null（挂平台根节点），非空必须是同平台已启用组织。
     *
     * <p>校验放在写入口而非出参组装：脏归属一旦落库，上级侧表现为"通道收得到但不挂在任何组织下"，
     * 排查时既不像数据错误也不像协议错误，成本很高。
     */
    private String normalizeOrgId(String platformId, String cascadeOrgId) {
        if (StringUtils.isBlank(cascadeOrgId)) {
            return null;
        }
        Assert.isTrue(cascadeOrgManager.isEnabledOrg(platformId, cascadeOrgId),
            "级联组织不存在或已停用: platformId=" + platformId + ", cascadeOrgId=" + cascadeOrgId);
        return cascadeOrgId;
    }

    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        return cascadeChannelService.removeById(id);
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
