package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.code.CascadeCodeResolver;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelBatchPublishDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelBatchPublishResultDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联上报通道管理器：目录里的通道节点 = 本地通道 + 该平台的发布配置。
 *
 * <p><b>存储只有发布层</b>：1.10.2 之前这里维护「每平台一行」的 {@code tb_cascade_channel}，
 * 通道因此带上平台维度——本地换一次组织要按平台改 N 次，也回答不了「这路通道推给了哪些平台」。
 * 收敛后本类只读写 {@code tb_cascade_publish}，名称、归属、本地启停、元数据全部由
 * {@link CascadePublishedAssetResolver} 从本地资产表派生，不再维护第二份真相。
 *
 * <p><b>组织归属只有一份，而且在设备上</b>：{@code id} 是发布行主键，{@code localDeviceId/localChannelId} 是本地资产标识，
 * {@code cascadeOrgId} 是这条通道在目录里归哪个组织——它由设备的组织推导，通道行上的 {@code org_id} 已停写。
 * 目录里挂在哪个父节点完全由设备组织推导（设备节点 → 设备组织 → 域节点），不再保存「按平台的父节点覆盖」，
 * 同一通道推给 N 个上级时结构一致。
 */
@Slf4j
@Component
public class CascadeChannelManager {

    /** 发布层对象类型：通道 */
    private static final String PUBLISH_TYPE = PublishObjectType.CHANNEL.name();

    @Autowired
    private CascadePublishManager cascadePublishManager;

    @Autowired
    private CascadePublishedAssetResolver publishedAssetResolver;

    @Autowired
    private DeviceChannelManager deviceChannelManager;

    @Autowired
    private DeviceManager        deviceManager;

    @Autowired
    private CascadeOrgManager cascadeOrgManager;

    @Autowired
    private CascadeCodeResolver cascadeCodeResolver;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 新增一条通道发布配置。
     *
     * <p><b>码的生成时序</b>：先建行、再生成、后回填——编码只能固化在已存在的发布行上，
     * 反过来（先生成再建行）算出来的码无处落笔，下次再进来必然换号。
     *
     * @param dto platformId / localChannelId 必填；localDeviceId 为空时按通道编码回查本地设备
     * @return 发布行主键
     */
    public Long add(CascadeChannelDTO dto) {
        return add(dto, null);
    }

    /**
     * 新增一条通道发布配置，复用调用方已算好的平台组织视图。
     *
     * @param platformView 该平台的组织视图；批量推送时传进来，避免每条都重算整棵组织树
     */
    private Long add(CascadeChannelDTO dto, CascadePlatformOrgView platformView) {
        Assert.notNull(dto, "dto 不能为空");
        Assert.hasText(dto.getPlatformId(), "platformId 不能为空");
        Assert.hasText(dto.getLocalChannelId(), "localChannelId 不能为空");
        String deviceId = resolveDeviceId(dto);

        CascadePublishDTO publish = new CascadePublishDTO();
        publish.setPlatformId(dto.getPlatformId());
        publish.setObjectType(PUBLISH_TYPE);
        publish.setObjectKey(PublishKeys.channel(deviceId, dto.getLocalChannelId()));
        /* 新增不允许静默覆盖：重复绑定会把已有编码/启停/元数据一并冲掉，只能走 update */
        Assert.isNull(cascadePublishManager.get(dto.getPlatformId(), PUBLISH_TYPE, publish.getObjectKey()),
            () -> "该平台已配置此通道: platformId=" + dto.getPlatformId()
                + ", channelId=" + dto.getLocalChannelId());
        /* 显式填了就用显式值（由 publish 统一标 MANUAL，此后规则不再改它）；
           留空先落空，由下面的 resolve 按规则生成并固化 */
        publish.setPublishCode(StringUtils.trimToNull(dto.getCascadeChannelId()));
        publish.setPublishName(dto.getCascadeName());
        /* 组织归属只看设备（通道行上的 org_id 已停写），发布行也不再保存按平台的父节点覆盖。
           落库前先确认设备组织在这个平台目录里立得起来，否则上级侧表现为「收到但挂不住」 */
        assertLocalOrgVisible(dto.getPlatformId(), dto.getLocalChannelId(), platformView);
        publish.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        copyMeta(dto, publish);
        Long id = cascadePublishManager.publish(publish);

        /* 发布行建好才谈得上固化：resolve 按「平台规则 → 组织规则」发号并写回 publish_code */
        String cascadeCode = cascadeCodeResolver.resolve(dto.getPlatformId(), PUBLISH_TYPE, publish.getObjectKey());
        /* 规则未启用时 resolve 只回本地码、不写库：这里必须补一次。
           上级点播/PTZ/录像反查只认 publish_code 列，留空会让原本能反查的通道集体失联 */
        cascadePublishManager.fillCodeIfBlank(id, cascadeCode, null, null);
        log.info("新增级联通道发布: id={}, platformId={}, deviceId={}, channelId={}, cascadeChannelId={}",
            id, dto.getPlatformId(), deviceId, dto.getLocalChannelId(), cascadeCode);
        return id;
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

    /**
     * 批量推送：一批本地通道 × 一批上级平台，逐条补齐发布行。
     *
     * <p><b>为什么不用 {@link #batchBind}</b>：那个方法一个平台一次调用、且只能给通道编码列表，
     * 既回答不了「一次推给多个上级」，也回答不了「这个组织下的通道全推上去」——后者需要先把
     * 组织解析成设备集合再取通道。
     *
     * <p><b>为什么每条仍走 {@link #add} 而不是批量直插发布行</b>：通道能出现在上级目录的前提是
     * 发布行带 {@code publish_code}。直插绕过 {@code add} 就不会发号、也不会校验组织可见性，
     * 上级点播/PTZ/录像反查不到本地通道。这里只把「一次批量该算一次」的东西提到循环外。
     *
     * @param req 平台集合 + 通道来源（显式勾选或按组织）
     * @return 逐类计数与失败明细
     */
    public CascadeChannelBatchPublishResultDTO batchPublish(CascadeChannelBatchPublishDTO req) {
        Assert.notNull(req, "req 不能为空");
        List<String> platformIds = distinctPlatformIds(req.getPlatformIds());
        Assert.notEmpty(platformIds, "至少选择一个上级平台");
        Assert.isTrue(req.getOrgId() != null
            || (req.getChannels() != null && !req.getChannels().isEmpty()),
            "请指定要推送的通道或组织");

        List<CascadeChannelBatchPublishResultDTO.Failure> failures = new ArrayList<>();
        List<CascadeChannelBatchPublishDTO.ChannelRef> channels = resolveChannelRefs(req, failures);

        /* 每个平台只在开始时取一次发布集：逐条 get() 会让「N 通道 × M 平台」退化成 N×M 次查库 */
        Map<String, Map<String, CascadePublishDTO>> existingByPlatform = new HashMap<>();
        for (String platformId : platformIds) {
            existingByPlatform.put(platformId,
                cascadePublishManager.indexByObjectKey(platformId, PUBLISH_TYPE, false));
        }
        Map<String, CascadePlatformOrgView> viewByPlatform = new HashMap<>();
        int created = 0;
        int skippedEnabled = 0;
        int skippedDisabled = 0;
        for (CascadeChannelBatchPublishDTO.ChannelRef channel : channels) {
            String objectKey = PublishKeys.channel(channel.getLocalDeviceId(), channel.getLocalChannelId());
            for (String platformId : platformIds) {
                CascadePublishDTO existed = existingByPlatform.get(platformId).get(objectKey);
                if (existed != null) {
                    /* 已存在只跳过，不自动改回上报：停报往往是上级要求下线，批量动作不该静默推翻它 */
                    if (Integer.valueOf(1).equals(existed.getEnabled())) {
                        skippedEnabled++;
                    } else {
                        skippedDisabled++;
                    }
                    continue;
                }
                try {
                    add(publishDto(platformId, channel), platformView(viewByPlatform, platformId));
                    created++;
                } catch (RuntimeException e) {
                    failures.add(failure(channel, platformId,
                        StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName())));
                }
            }
        }

        CascadeChannelBatchPublishResultDTO result = new CascadeChannelBatchPublishResultDTO();
        result.setChannelCount(channels.size());
        result.setPlatformCount(platformIds.size());
        result.setCreated(created);
        result.setSkippedEnabled(skippedEnabled);
        result.setSkippedDisabled(skippedDisabled);
        result.setFailures(failures);
        log.info("批量推送级联通道: platformIds={}, 通道={}, 新增={}, 已上报跳过={}, 停报跳过={}, 失败={}",
            platformIds, channels.size(), created, skippedEnabled, skippedDisabled, failures.size());
        return result;
    }

    /**
     * 按本地通道编码查该平台的发布配置。
     *
     * <p>推送通知（NOTIFY）只带本地通道编码，需要先补齐设备段才能拼出业务键。
     */
    public CascadeChannelDTO getByPlatformAndChannel(String platformId, String localChannelId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(localChannelId)) {
            return null;
        }
        DeviceChannelDO local = deviceChannelManager.getByChannelId(localChannelId);
        if (local == null) {
            return null;
        }
        return publishedAssetResolver.findChannel(platformId,
            PublishKeys.channel(local.getDeviceId(), local.getChannelId()));
    }

    /**
     * 按上级眼中的编码查找：点播、录像查询、PTZ 控制都从这里反查本地通道。
     *
     * <p>反查不看启停：停用只表示「不再新上报」，正在进行的点播不该被掐断。
     */
    public CascadeChannelDTO getByPlatformAndCascadeChannelId(String platformId, String cascadeChannelId) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(cascadeChannelId)) {
            return null;
        }
        CascadePublishDTO publish = cascadePublishManager.getByCode(platformId, PUBLISH_TYPE, cascadeChannelId);
        return publish == null ? null : publishedAssetResolver.findChannel(platformId, publish.getObjectKey());
    }

    /**
     * 该平台目录里会出现的通道（已过滤停用）。
     *
     * <p>上级台账的「视频输入通道数」用它计数：只算真会上报的，停用行不能虚增台数。
     */
    public List<CascadeChannelDTO> listEnabledChannels(String platformId) {
        return publishedAssetResolver.listChannels(platformId);
    }

    /** 按发布行主键取通道视图（不看启停）：页面详情要能打开已停用的行。 */
    public CascadeChannelDTO getById(Long id) {
        return publishedAssetResolver.getChannel(id);
    }

    public boolean deleteOne(CascadeChannelDTO dto) {
        Assert.notNull(dto, "dto 不能为空");
        CascadeChannelDTO existed = dto.getId() != null
            ? getById(dto.getId())
            : getByPlatformAndChannel(dto.getPlatformId(), dto.getLocalChannelId());
        return existed != null && delete(existed.getId());
    }

    /**
     * 更新级联通道发布配置。
     *
     * <p><b>不再接受「按平台的父节点覆盖」</b>：目录里挂在哪个节点完全由设备的组织归属推导
     * （设备节点 → 设备组织 → 域节点）。想改挂位置就去改设备归属。
     *
     * <p><b>启停不只是改字段</b>：{@code 1 -> 0} 必须显式推 {@code Event=DEL}，否则上级目录里的
     * 通道会永久留着（快照是增量 upsert 语义，不会因为节点「这次没出现」就删）；
     * {@code 0 -> 1} 同理需要 {@code ADD}，否则要等到下一次整树重推才回来。
     */
    public boolean update(CascadeChannelDTO dto) {
        Assert.notNull(dto.getId(), "id 不能为空");
        CascadeChannelDTO existed = getById(dto.getId());
        Assert.notNull(existed, "级联通道不存在: id=" + dto.getId());
        String  platformId = existed.getPlatformId();
        String  objectKey  = objectKeyOf(existed);
        boolean wasEnabled = existed.getEnabled() == null || existed.getEnabled() == 1;
        boolean nowEnabled = dto.getEnabled() == null ? wasEnabled : dto.getEnabled() == 1;
        /* 0 -> 1 是「重新上报」：归属组织的整条祖先链都得在报，否则推上去又是一条孤儿 */
        if (!wasEnabled && nowEnabled) {
            assertParentVisible(platformId, existed.getCascadeOrgId());
        }

        CascadePublishDTO patch = new CascadePublishDTO();
        patch.setPlatformId(platformId);
        patch.setObjectType(PUBLISH_TYPE);
        patch.setObjectKey(objectKey);
        /* 编码三态：空白 = 不动（updateById 跳过 null），非空 = 覆盖。更新时不派生本地编码：
           「这次没传」与「要改回本地编码」必须能区分，否则前端只改名称就会把人工编码冲掉。
           非空且与现值不同即视为人工改写，由 publish 标 code_source=MANUAL（值没变则不翻来源） */
        patch.setPublishCode(StringUtils.trimToNull(dto.getCascadeChannelId()));
        patch.setPublishName(dto.getCascadeName());
        patch.setEnabled(dto.getEnabled());
        copyMeta(dto, patch);
        cascadePublishManager.publish(patch);

        if (wasEnabled && !nowEnabled) {
            /* 删的是上级眼里那个节点，编码变过也要用改之前的 */
            publishEvent(platformId, existed.getCascadeChannelId(), "DEL");
        } else if (!wasEnabled && nowEnabled) {
            publishEvent(platformId,
                StringUtils.defaultIfBlank(dto.getCascadeChannelId(), existed.getCascadeChannelId()), "ADD");
        }
        return true;
    }

    /**
     * 取消单条通道同步：不再上报，记录保留可恢复。
     *
     * @param id 发布行主键
     * @return 是否变更（已处于停报状态时幂等返回 true）
     */
    public boolean revoke(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeChannelDTO existed = getById(id);
        Assert.notNull(existed, "级联通道不存在: id=" + id);
        if (existed.getEnabled() != null && existed.getEnabled() == 0) {
            return true;
        }
        boolean ok = cascadePublishManager.setEnabled(existed.getPlatformId(), PUBLISH_TYPE,
            objectKeyOf(existed), 0);
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
        assertParentVisible(existed.getPlatformId(), existed.getCascadeOrgId());
        boolean ok = cascadePublishManager.setEnabled(existed.getPlatformId(), PUBLISH_TYPE,
            objectKeyOf(existed), 1);
        if (ok) {
            log.info("恢复级联通道同步: id={}, cascadeChannelId={}", id, existed.getCascadeChannelId());
            publishEvent(existed.getPlatformId(), existed.getCascadeChannelId(), "ADD");
        }
        return ok;
    }

    /**
     * 删除一条通道发布：上级目录里显式推一次 {@code DEL}，否则快照 upsert 永远不会删它。
     */
    public boolean delete(Long id) {
        Assert.notNull(id, "id 不能为空");
        CascadeChannelDTO existed = getById(id);
        if (existed == null) {
            return false;
        }
        boolean ok = cascadePublishManager.unpublish(existed.getPlatformId(), PUBLISH_TYPE, objectKeyOf(existed));
        if (ok) {
            publishDeleted(existed);
        }
        return ok;
    }

    public Page<CascadeChannelDTO> getPage(CascadeChannelDTO query, int page, int size) {
        return getPage(query, page, size, false);
    }

    /**
     * 分页查询级联通道。
     *
     * <p>{@code includeSubOrg} 决定 {@code cascadeOrgId} 的语义：{@code false} 只取直属该组织的通道，
     * {@code true} 取整棵子树。组织层数再多也不需要递归 SQL——子树编码集合本身是一条前缀匹配查询拿到的。
     *
     * <p><b>为什么最后一步在内存分页</b>：本地设备/通道标识在发布表里合成了一个业务键，
     * 逐段精确匹配无法全部下推到索引；单平台通道数在千级以下，一次投影比分页拼 SQL 更稳，
     * 也保证「孤儿行过滤 + 条件匹配」只有一套口径。R5 前端改为以本地通道为主键后会重新设计这个列表。
     *
     * @param includeSubOrg 是否把子组织下的通道一并纳入
     */
    public Page<CascadeChannelDTO> getPage(CascadeChannelDTO query, int page, int size, boolean includeSubOrg) {
        CascadeChannelDTO condition = query == null ? new CascadeChannelDTO() : query;
        List<CascadePublishDTO> rows = StringUtils.isNotBlank(condition.getPlatformId())
            ? cascadePublishManager.listByPlatform(condition.getPlatformId(), PUBLISH_TYPE, false)
            : cascadePublishManager.listByType(PUBLISH_TYPE, false);
        Set<String> orgFilter = orgFilter(condition.getCascadeOrgId(), includeSubOrg);
        if (orgFilter != null && orgFilter.isEmpty()) {
            /* 组织不存在或已删除：返回空页，绝不能退化成"不过滤"把全平台通道吐出去 */
            return new Page<>(page, size, 0);
        }

        List<CascadeChannelDTO> records = new ArrayList<>();
        for (CascadeChannelDTO each : publishedAssetResolver.projectChannels(rows)) {
            if (matches(each, condition, orgFilter)) {
                records.add(each);
            }
        }
        /* 新建的排前面：与原来的 create_time desc 一致 */
        records.sort(Comparator.comparing(CascadeChannelDTO::getCreateTime,
            Comparator.nullsLast(Comparator.reverseOrder())));
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(records.size(), from + size);
        Page<CascadeChannelDTO> result = new Page<>(page, size, records.size());
        result.setRecords(from >= to ? new ArrayList<>() : new ArrayList<>(records.subList(from, to)));
        return result;
    }

    /**
     * 组织筛选集合：空白归属不过滤；组织不存在时返回空集合（由调用方转成空页）。
     */
    private Set<String> orgFilter(String cascadeOrgId, boolean includeSubOrg) {
        if (StringUtils.isBlank(cascadeOrgId)) {
            return null;
        }
        List<String> codes = includeSubOrg
            ? cascadeOrgManager.listSubtreeCodes(cascadeOrgId, true)
            : List.of(cascadeOrgId);
        return new HashSet<>(codes);
    }

    /**
     * 分页条件匹配。
     *
     * <p>{@code cascadeOrgId} 只比本地归属：发布层不再有「按平台的父节点覆盖」，
     * 不存在「归属对不上但覆盖对得上」的通道。
     */
    private boolean matches(CascadeChannelDTO channel, CascadeChannelDTO condition, Set<String> orgFilter) {
        if (StringUtils.isNotBlank(condition.getLocalDeviceId())
            && !StringUtils.equals(condition.getLocalDeviceId(), channel.getLocalDeviceId())) {
            return false;
        }
        if (StringUtils.isNotBlank(condition.getLocalChannelId())
            && !StringUtils.equals(condition.getLocalChannelId(), channel.getLocalChannelId())) {
            return false;
        }
        if (StringUtils.isNotBlank(condition.getCascadeChannelId())
            && !StringUtils.equals(condition.getCascadeChannelId(), channel.getCascadeChannelId())) {
            return false;
        }
        if (orgFilter == null) {
            return true;
        }
        return channel.getCascadeOrgId() != null && orgFilter.contains(channel.getCascadeOrgId());
    }

    /**
     * 补齐发布行业务键里的设备段。
     *
     * <p>入参可能只给通道编码（老的绑定页就是如此）；国标通道 ID 全局唯一，回本地表查一次即可。
     * 两个都补不上就直接拒绝：没有设备段的业务键会让不同设备的同名通道互相覆盖。
     */
    private String resolveDeviceId(CascadeChannelDTO dto) {
        String deviceId = StringUtils.trimToNull(dto.getLocalDeviceId());
        if (deviceId != null) {
            return deviceId;
        }
        DeviceChannelDO local = deviceChannelManager.getByChannelId(dto.getLocalChannelId());
        Assert.notNull(local, "本地通道不存在，无法确定所属设备: channelId=" + dto.getLocalChannelId());
        dto.setLocalDeviceId(local.getDeviceId());
        return local.getDeviceId();
    }

    /**
     * 目标通道：按组织解析优先，其次用显式勾选集。
     *
     * <p>通道行上的 {@code org_id} 已停写（恒 NULL），所以「按组织取通道」只能先算出该组织范围内的
     * 设备集合再取通道——与设备页组织树同一口径，不能直接按通道表筛。
     *
     * <p>解析不出来的通道不静默丢弃：逐条记进 {@code failures}，否则用户只会看到「推了 50 条只成功 30 条」
     * 而不知道另外 20 条去哪了。
     */
    private List<CascadeChannelBatchPublishDTO.ChannelRef> resolveChannelRefs(
        CascadeChannelBatchPublishDTO req, List<CascadeChannelBatchPublishResultDTO.Failure> failures) {
        List<CascadeChannelBatchPublishDTO.ChannelRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (req.getOrgId() != null) {
            List<String> deviceIds = deviceManager.listDeviceIdsByOrgScope(req.getOrgId(),
                !Boolean.FALSE.equals(req.getIncludeChildren()));
            for (DeviceChannelDO channel : deviceChannelManager.listByDeviceIds(deviceIds)) {
                addRef(refs, seen, channel.getDeviceId(), channel.getChannelId());
            }
            return refs;
        }
        if (req.getChannels() == null) {
            return refs;
        }
        for (CascadeChannelBatchPublishDTO.ChannelRef item : req.getChannels()) {
            if (item == null || StringUtils.isBlank(item.getLocalChannelId())) {
                continue;
            }
            String channelId = item.getLocalChannelId().trim();
            /* 设备段能省则省，但落库前必须确认真有这条通道：凭空造一条发布行会在上级目录里
               留下挂不住的孤儿节点，且本地删通道时也清不掉它 */
            String deviceId = StringUtils.trimToNull(item.getLocalDeviceId());
            DeviceChannelDO local = deviceId == null
                ? deviceChannelManager.getByChannelId(channelId)
                : deviceChannelManager.getByDeviceId(deviceId, channelId);
            if (local == null) {
                failures.add(failure(deviceId, channelId, null, "本地通道不存在"));
                continue;
            }
            addRef(refs, seen, local.getDeviceId(), local.getChannelId());
        }
        return refs;
    }

    private void addRef(List<CascadeChannelBatchPublishDTO.ChannelRef> refs, Set<String> seen,
        String deviceId, String channelId) {
        if (StringUtils.isAnyBlank(deviceId, channelId)) {
            return;
        }
        /* 勾选与组织两种来源可能重叠，同一业务键只处理一次，否则第二次会撞 add 的重复校验 */
        if (!seen.add(PublishKeys.channel(deviceId, channelId))) {
            return;
        }
        CascadeChannelBatchPublishDTO.ChannelRef ref = new CascadeChannelBatchPublishDTO.ChannelRef();
        ref.setLocalDeviceId(deviceId);
        ref.setLocalChannelId(channelId);
        refs.add(ref);
    }

    /** 通道推送 DTO：只带平台与本地标识，编码/名称留空由编码规则派生。 */
    private CascadeChannelDTO publishDto(String platformId, CascadeChannelBatchPublishDTO.ChannelRef channel) {
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setPlatformId(platformId);
        dto.setLocalDeviceId(channel.getLocalDeviceId());
        dto.setLocalChannelId(channel.getLocalChannelId());
        return dto;
    }

    /** 平台组织视图：批量推送里每个平台只算一次（现算是「取组织发布集 + 遍历全量组织」）。 */
    private CascadePlatformOrgView platformView(Map<String, CascadePlatformOrgView> cache, String platformId) {
        CascadePlatformOrgView view = cache.get(platformId);
        if (view == null) {
            view = cascadeOrgManager.platformOrgView(platformId);
            cache.put(platformId, view);
        }
        return view;
    }

    private CascadeChannelBatchPublishResultDTO.Failure failure(
        CascadeChannelBatchPublishDTO.ChannelRef channel, String platformId, String reason) {
        return failure(channel.getLocalDeviceId(), channel.getLocalChannelId(), platformId, reason);
    }

    private CascadeChannelBatchPublishResultDTO.Failure failure(String deviceId, String channelId,
        String platformId, String reason) {
        CascadeChannelBatchPublishResultDTO.Failure failure = new CascadeChannelBatchPublishResultDTO.Failure();
        failure.setLocalDeviceId(deviceId);
        failure.setLocalChannelId(channelId);
        failure.setPlatformId(platformId);
        failure.setReason(reason);
        return failure;
    }

    /** 平台编码去空白、去重：同一平台重复出现会让每条通道被处理两次。 */
    private List<String> distinctPlatformIds(List<String> platformIds) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (platformIds == null) {
            return result;
        }
        for (String platformId : platformIds) {
            String trimmed = StringUtils.trimToNull(platformId);
            if (trimmed != null && seen.add(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 目录项元数据：null 不改、空串改回自动派生、非空为覆盖值。 */
    private void copyMeta(CascadeChannelDTO dto, CascadePublishDTO publish) {
        publish.setManufacturer(dto.getManufacturer());
        publish.setModel(dto.getModel());
        publish.setAddress(dto.getAddress());
        publish.setIpAddress(dto.getIpAddress());
        publish.setPtzType(dto.getPtzType());
    }

    /** 发布行业务键：{@code deviceId:channelId} */
    private String objectKeyOf(CascadeChannelDTO channel) {
        Assert.hasText(channel.getLocalDeviceId(), "通道缺少设备标识: channelId=" + channel.getLocalChannelId());
        Assert.hasText(channel.getLocalChannelId(), "通道缺少本地通道标识");
        return PublishKeys.channel(channel.getLocalDeviceId(), channel.getLocalChannelId());
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
     * 校验通道所属设备的组织在这个平台目录里立得起来。
     *
     * <p>通道没有独立归属，决定它挂在哪里的是<b>设备</b>的组织，校验因此落在写入口：
     * 脏归属一旦落库，上级侧表现为「通道收得到但不挂在任何组织下」，或者被快照直接剪掉
     * （组织取消同步且策略为 CASCADE）——页面显示上报中，上级却一条也看不到。
     *
     * <p>设备没有归属、或归属的组织已被删除时放行：这两种情况下上级侧是「挂到域节点」，不是脏数据。
     */
    private void assertLocalOrgVisible(String platformId, String localChannelId,
        CascadePlatformOrgView platformView) {
        DeviceChannelDO local = deviceChannelManager.getByChannelId(localChannelId);
        if (local == null) {
            return;
        }
        DeviceDTO device = deviceManager.getDtoByDeviceId(local.getDeviceId());
        Long orgId = device == null ? null : device.getOrgId();
        if (orgId == null) {
            return;
        }
        CascadeOrgDTO org = cascadeOrgManager.getById(orgId);
        if (org != null) {
            assertVisible(platformId, org, platformView);
        }
    }

    /**
     * 组织可见性校验（容错版）：编码查不到组织时直接放行。
     *
     * <p>通道可能根本没设归属（{@code org_id} 为空，SIP 自动注册带进来的通道就是这样），
     * 也可能挂的是设备组织。两类情况上级侧都是「挂到域节点」，不是脏数据，不该在这里
     * 误报「组织不存在」。
     */
    private void assertParentVisible(String platformId, String parentCode) {
        if (StringUtils.isBlank(parentCode)) {
            return;
        }
        CascadeOrgDTO org = cascadeOrgManager.getByCode(parentCode);
        if (org != null) {
            assertVisible(platformId, org);
        }
    }

    /**
     * 校验组织确实会在该平台的目录里出现。
     *
     * <p>判据不再是「组织 enabled=1」：本地启用只是必要条件，真正决定上级能不能看到的是
     * {@code platformOrgView}——本地整条祖先链启用 ∩ 该平台已发布，或虽然立不起来但配置了
     * {@code REROUTE}（通道上浮挂域节点，同样会出现在目录里）。
     */
    private void assertVisible(String platformId, CascadeOrgDTO org) {
        assertVisible(platformId, org, null);
    }

    /**
     * 校验组织确实会在该平台的目录里出现（可复用已算好的平台视图）。
     *
     * <p>判据不再是「组织 enabled=1」：本地启用只是必要条件，真正决定上级能不能看到的是
     * {@code platformOrgView}——本地整条祖先链启用 ∩ 该平台已发布，或虽然立不起来但配置了
     * {@code REROUTE}（通道上浮挂域节点，同样会出现在目录里）。
     *
     * @param platformView 该平台的组织视图；{@code null} 时现算
     */
    private void assertVisible(String platformId, CascadeOrgDTO org, CascadePlatformOrgView platformView) {
        String cascadeOrgId = org.getOrgCode();
        CascadePlatformOrgView view = platformView != null ? platformView
            : cascadeOrgManager.platformOrgView(platformId);
        boolean inCatalog = view.catalogOrgs().stream()
            .anyMatch(each -> cascadeOrgId.equals(each.getLocalCode()));
        if (inCatalog || view.reroutedLocalCodes().contains(cascadeOrgId)) {
            return;
        }
        String name = StringUtils.defaultIfBlank(org.getOrgName(), cascadeOrgId);
        throw new IllegalArgumentException("级联组织【" + name + "】不会出现在平台【" + platformId
            + "】的目录里：请先把该组织及其上级链发布到这个平台，或在发布配置里改用 REROUTE");
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
        publishEvent(existed.getPlatformId(), existed.getCascadeChannelId(), "DEL");
    }
}
