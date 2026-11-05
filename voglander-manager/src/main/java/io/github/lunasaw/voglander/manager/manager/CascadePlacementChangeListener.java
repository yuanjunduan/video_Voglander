package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PlacementScope;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.common.event.LocalCascadePlacementChangeEvent;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformOrgView;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 设备 / 通道改了归属组织后，把上级目录里的<b>旧位置</b>删掉。
 *
 * <p><b>不修会发生什么</b>：目录快照对上级是增量 upsert 语义，新一轮快照只会把节点写到新组织
 * 下面，旧组织下那一份原封不动留着。结果同一个通道在上级目录里挂两处、两处都能点播，
 * 而本地只有一条通道——排查时从任何一侧都看不出问题。
 *
 * <p><b>为什么算旧父节点要用「旧归属覆盖 + 同一套解析」</b>：事件是提交之后才处理的，此刻库里
 * 已经是新归属。把旧组织编码盖回 DTO 上再跑一次 {@link CascadePlacementIndex}，等价于
 * 「变更前那一刻的目录」，不需要在写入前抢拍快照，也不会与组装器各算一套。
 *
 * <p><b>为什么用 {@code 旧父 != null && 旧父 != 新父}</b> 作唯一触发条件：
 * <ul>
 *   <li>旧父为 null：原本就不上报，上级没有旧节点，交给快照 upsert 即可；</li>
 *   <li>新旧相同：挂载位置没变，发 DEL 会把刚推上去的节点又删掉一次；</li>
 *   <li>其余情况（换组织、新组织在该平台不可见而被剔除、设备层开关导致落到组织）都要删旧位置。</li>
 * </ul>
 *
 * <p><b>为什么用 {@link TransactionPhase#AFTER_COMMIT}</b>：监听器要重新读组织视图与发布集，
 * 提交前读到的是旧数据，删完再把旧结构推回上级等于白做。提交之后才发，快照重推才看得见新归属。
 *
 * <p><b>为什么不让异常外抛</b>：归属变更已经落库，删除报文只是让上级目录跟上；把它抛到请求上
 * 会让用户看到「保存失败」，实际本地已经改好。失败只记日志，下一次快照仍会把节点放到新位置。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadePlacementChangeListener {

    /** 目录节点变更类型：删除 */
    private static final String EVENT_DELETE  = "DEL";

    private final CascadePlatformManager        cascadePlatformManager;
    private final CascadeOrgManager             cascadeOrgManager;
    private final CascadeOrgService             cascadeOrgService;
    private final CascadePublishManager         cascadePublishManager;
    private final CascadePublishedAssetResolver publishedAssetResolver;
    private final ApplicationEventPublisher     eventPublisher;

    /**
     * 归属变更 → 逐平台计算「原来挂在哪」，发 {@code DEL} 后由事件桥接重推快照。
     *
     * @param event 归属变更事件；同一目标、同一归属的重复事件直接忽略
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlacementChange(LocalCascadePlacementChangeEvent event) {
        if (event == null || StringUtils.isBlank(event.getObjectKey())) {
            return;
        }
        if (Objects.equals(event.getPreviousOrgId(), event.getCurrentOrgId())) {
            return;
        }
        try {
            revokeOldPlacement(event);
        } catch (Exception e) {
            /* 本地已改好，旧位置没删掉只影响上级目录；抛出去只会让用户以为保存失败 */
            log.error("归属变更后通知上级删旧位置失败: scope={}, objectKey={}, previousOrgId={}, currentOrgId={}",
                event.getScope(), event.getObjectKey(), event.getPreviousOrgId(), event.getCurrentOrgId(), e);
        }
    }

    private void revokeOldPlacement(LocalCascadePlacementChangeEvent event) {
        Map<Long, String> orgCodeById = orgCodeById();
        String previousOrgCode = event.getPreviousOrgId() == null ? null : orgCodeById.get(event.getPreviousOrgId());
        String currentOrgCode = event.getCurrentOrgId() == null ? null : orgCodeById.get(event.getCurrentOrgId());
        if (event.getPreviousOrgId() != null && previousOrgCode == null) {
            /* 旧组织已被物理删除：它在上级目录里的节点早随组织删除一起撤掉了 */
            log.debug("旧归属组织已不存在，无需删旧位置: orgId={}", event.getPreviousOrgId());
            return;
        }

        List<CascadePublishDTO> rows = affectedRows(event);
        if (rows.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<CascadePublishDTO>> entry : groupByPlatform(rows).entrySet()) {
            String platformId = entry.getKey();
            CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
            if (platform == null) {
                log.warn("归属变更通知跳过，发布行指向的上级平台已不存在: platformId={}, scope={}, objectKey={}",
                    platformId, event.getScope(), event.getObjectKey());
                continue;
            }
            List<LocalCascadeCatalogChangeEvent.Node> nodes = buildNodes(platform, platformId,
                event.getScope(), event.getObjectKey(), previousOrgCode, currentOrgCode);
            if (nodes.isEmpty()) {
                continue;
            }
            /* 事件桥接在 DEL 之后会重推快照：删除只是把旧位置抹掉，新位置由快照 upsert 建立 */
            eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(platformId, event.getScope(),
                event.getObjectKey(), EVENT_DELETE, nodes));
            log.info("归属变更，通知上级删旧位置: platformId={}, scope={}, objectKey={}, 旧组织={}, 新组织={}, 节点数={}",
                platformId, event.getScope(), event.getObjectKey(), previousOrgCode, currentOrgCode, nodes.size());
        }
    }

    /**
     * 受影响的启用发布行。
     *
     * <p>停用的行不发：上级目录里本来就没有它（停用时会显式 {@code DEL}），再删一次只会多一趟往返。
     */
    private List<CascadePublishDTO> affectedRows(LocalCascadePlacementChangeEvent event) {
        List<CascadePublishDTO> rows = new ArrayList<>();
        if (PlacementScope.DEVICE.equalsIgnoreCase(event.getScope())) {
            /* 设备自己那一行 + 名下全部通道的发布行：通道是否跟着动由下面逐条对比新旧父节点决定 */
            String deviceId = event.getObjectKey();
            rows.addAll(cascadePublishManager.listByObjectKeys(PublishObjectType.DEVICE.name(), List.of(deviceId)));
            for (CascadePublishDTO row : cascadePublishManager.listByObjectKeyPrefix(
                PublishObjectType.CHANNEL.name(), deviceId + PublishKeys.CHANNEL_SEPARATOR)) {
                if (deviceId.equals(PublishKeys.channelDeviceId(row.getObjectKey()))) {
                    rows.add(row);
                }
            }
        } else {
            rows.addAll(cascadePublishManager.listByObjectKeys(PublishObjectType.CHANNEL.name(),
                List.of(event.getObjectKey())));
        }
        List<CascadePublishDTO> enabled = new ArrayList<>(rows.size());
        for (CascadePublishDTO row : rows) {
            if (row != null && Integer.valueOf(1).equals(row.getEnabled())) {
                enabled.add(row);
            }
        }
        return enabled;
    }

    private Map<String, List<CascadePublishDTO>> groupByPlatform(List<CascadePublishDTO> rows) {
        Map<String, List<CascadePublishDTO>> byPlatform = new LinkedHashMap<>();
        for (CascadePublishDTO row : rows) {
            if (StringUtils.isBlank(row.getPlatformId())) {
                continue;
            }
            byPlatform.computeIfAbsent(row.getPlatformId(), key -> new ArrayList<>()).add(row);
        }
        return byPlatform;
    }

    /**
     * 一个平台上的待删节点清单（已按「叶子 → 根」排序：通道在前，设备在后）。
     */
    private List<LocalCascadeCatalogChangeEvent.Node> buildNodes(CascadePlatformDTO platform, String platformId,
        String scope, String objectKey, String previousOrgCode, String currentOrgCode) {
        String domainId = StringUtils.defaultIfBlank(platform.getLocalClientId(), platformId);
        CascadePlatformOrgView orgView = cascadeOrgManager.platformOrgView(platformId);
        /* 两份索引的组织部分完全相同：变的是资产归属，不是组织树 */
        CascadePlacementIndex current = indexOf(domainId, orgView);
        CascadePlacementIndex legacy = indexOf(domainId, orgView);

        if (platform.useDeviceLayer()) {
            /*
             * 设备入索引的条件必须与组装器一致（父节点可见才出现）：通道的父节点解析要靠它判断
             * 「设备层到底有没有那个设备节点」。当前视图读库即新归属；旧视图只把目标设备的归属盖回旧值。
             */
            for (CascadePlatformDeviceDTO device : publishedAssetResolver.listDevices(platformId)) {
                if (current.resolveDeviceParent(device) != null) {
                    current.addDevice(device);
                }
                CascadePlatformDeviceDTO previous = withDeviceOrg(device,
                    isTarget(device.getLocalDeviceId(), objectKey) ? previousOrgCode : device.getLocalOrgCode());
                if (legacy.resolveDeviceParent(previous) != null) {
                    legacy.addDevice(previous);
                }
            }
        }

        List<LocalCascadeCatalogChangeEvent.Node> nodes = new ArrayList<>(
            channelNodes(platformId, scope, objectKey, previousOrgCode, current, legacy));
        /* 设备节点只在设备层开着时存在于上级目录：关着还发 DEL 是对不存在的节点做删除 */
        if (platform.useDeviceLayer() && PlacementScope.DEVICE.equalsIgnoreCase(scope)) {
            LocalCascadeCatalogChangeEvent.Node deviceNode = deviceNode(platformId, objectKey,
                previousOrgCode, currentOrgCode, current, legacy);
            if (deviceNode != null) {
                nodes.add(deviceNode);
            }
        }
        return nodes;
    }

    /**
     * 通道节点：父节点真的变了才删。
     *
     * <p>设备改组织时只影响<b>跟随设备</b>的通道（自身无组织）；通道自己有组织时第 1 级就命中，
     * 新旧父相同，自然被过滤掉。用新旧对比而不是「是否跟随」来判断，分母才不会随规则演进而漏。
     */
    private List<LocalCascadeCatalogChangeEvent.Node> channelNodes(String platformId, String scope,
        String objectKey, String previousOrgCode, CascadePlacementIndex current, CascadePlacementIndex legacy) {
        boolean channelScope = PlacementScope.CHANNEL.equalsIgnoreCase(scope);
        List<CascadePublishDTO> rows;
        if (channelScope) {
            rows = cascadePublishManager.listByObjectKeys(PublishObjectType.CHANNEL.name(), List.of(objectKey));
        } else {
            rows = cascadePublishManager.listByObjectKeyPrefix(PublishObjectType.CHANNEL.name(),
                objectKey + PublishKeys.CHANNEL_SEPARATOR);
        }
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<LocalCascadeCatalogChangeEvent.Node> nodes = new ArrayList<>();
        for (CascadeChannelDTO channel : publishedAssetResolver.projectChannels(rows)) {
            if (!Integer.valueOf(1).equals(channel.getEnabled())) {
                continue;
            }
            /* 前缀可能捞到脏键：按设备段复核一次，别把别的设备的通道一起删了 */
            if (!channelScope && !objectKey.equals(channel.getLocalDeviceId())) {
                continue;
            }
            /* 一次变更只动一个字段：通道换组织盖「自己」，设备换组织盖「设备段」 */
            CascadeChannelDTO previous = channelScope
                ? withChannelOwnership(channel, previousOrgCode, channel.getDeviceOrgCode())
                : withChannelOwnership(channel, channel.getCascadeOrgId(), previousOrgCode);
            String oldParent = legacy.resolveChannelParent(previous);
            String newParent = current.resolveChannelParent(channel);
            if (oldParent == null || Objects.equals(oldParent, newParent)) {
                continue;
            }
            nodes.add(new LocalCascadeCatalogChangeEvent.Node(channel.getCascadeChannelId(),
                StringUtils.defaultIfBlank(channel.getCascadeName(), channel.getCascadeChannelId()), oldParent));
        }
        return nodes;
    }

    /**
     * 设备节点：设备层开着才有这个节点，父节点（所属组织）变了才删。
     */
    private LocalCascadeCatalogChangeEvent.Node deviceNode(String platformId, String deviceId,
        String previousOrgCode, String currentOrgCode, CascadePlacementIndex current,
        CascadePlacementIndex legacy) {
        CascadePublishDTO publish = cascadePublishManager.get(platformId, PublishObjectType.DEVICE.name(), deviceId);
        if (publish == null || !Integer.valueOf(1).equals(publish.getEnabled())) {
            return null;
        }
        String oldParent = legacy.resolveDeviceParent(deviceView(deviceId, previousOrgCode));
        String newParent = current.resolveDeviceParent(deviceView(deviceId, currentOrgCode));
        if (oldParent == null || Objects.equals(oldParent, newParent)) {
            return null;
        }
        String cascadeId = CascadeCodeBuilder.effectiveCode(publish.getPublishCode(), deviceId);
        return new LocalCascadeCatalogChangeEvent.Node(cascadeId,
            StringUtils.defaultIfBlank(publish.getPublishName(), cascadeId), oldParent);
    }

    // ----------------------------------------------------------------------- 内部

    private CascadePlacementIndex indexOf(String domainId, CascadePlatformOrgView orgView) {
        CascadePlacementIndex index = CascadePlacementIndex.of(domainId, orgView);
        if (orgView != null) {
            for (CascadePlatformOrgDTO org : orgView.catalogOrgs()) {
                index.addOrg(org);
            }
        }
        return index;
    }

    private Map<Long, String> orgCodeById() {
        Map<Long, String> index = new HashMap<>();
        for (CascadeOrgDO org : cascadeOrgService.list()) {
            if (org.getId() != null && StringUtils.isNotBlank(org.getOrgCode())) {
                index.put(org.getId(), org.getOrgCode());
            }
        }
        return index;
    }

    /** 只覆盖归属字段的副本：父节点解析只读这两处，其余字段原样带过以免误导后续维护。 */
    private CascadeChannelDTO withChannelOwnership(CascadeChannelDTO source, String ownOrg, String deviceOrg) {
        CascadeChannelDTO copy = new CascadeChannelDTO();
        copy.setCascadeChannelId(source.getCascadeChannelId());
        copy.setCascadeName(source.getCascadeName());
        copy.setLocalDeviceId(source.getLocalDeviceId());
        copy.setLocalChannelId(source.getLocalChannelId());
        copy.setEnabled(source.getEnabled());
        copy.setCascadeOrgId(ownOrg);
        copy.setDeviceOrgCode(deviceOrg);
        return copy;
    }

    private CascadePlatformDeviceDTO withDeviceOrg(CascadePlatformDeviceDTO source, String orgCode) {
        CascadePlatformDeviceDTO copy = new CascadePlatformDeviceDTO();
        copy.setLocalDeviceId(source.getLocalDeviceId());
        copy.setPlatformCode(source.getPlatformCode());
        copy.setName(source.getName());
        copy.setLocalOrgCode(orgCode);
        return copy;
    }

    /** 只用于解析父节点的最小设备视图：父节点只看「这个设备归哪个组织」。 */
    private CascadePlatformDeviceDTO deviceView(String deviceId, String orgCode) {
        CascadePlatformDeviceDTO view = new CascadePlatformDeviceDTO();
        view.setLocalDeviceId(deviceId);
        view.setLocalOrgCode(orgCode);
        return view;
    }

    private boolean isTarget(String candidate, String objectKey) {
        return StringUtils.isNotBlank(candidate) && candidate.equals(objectKey);
    }
}
