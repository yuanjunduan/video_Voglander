package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import io.github.lunasaw.voglander.manager.service.CascadeOrgService;
import io.github.lunasaw.voglander.repository.entity.CascadeOrgDO;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.repository.entity.DeviceDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 发布层 → 目录视图的读取入口：把 {@code tb_cascade_publish} 的通道/设备发布行翻译成“上级会看到什么”。
 *
 * <p><b>为什么单独一个组件，而不是写在组装器里</b>：目录组装与「停用组织时该删哪些通道」是同一份数据
 * 的两种用法。如果组装器按发布集算、DEL 集合按 {@code tb_cascade_channel} 算，两边迟早不一致——
 * 表现为「推上去的目录」和「删掉的目录」各自正确，合起来却对不上。两者共用本组件，判断口径只有一处。
 *
 * <p><b>为什么必须是派生视图</b>：发布行只存业务键与覆盖值（编码/名称/启停/元数据），节点的名称、
 * 本地归属组织、本地启停、在线状态都在本地资产表里。把它们冗余进发布表就等于维护第二份真相，
 * 设备改名、通道换归属都要同步两处。
 *
 * <p><b>过滤口径</b>：
 * <ul>
 *   <li>发布行 {@code enabled=0} → 不上报（只影响该平台）</li>
 *   <li>本地通道/设备 {@code enabled=0} → 不上报（影响所有平台）</li>
 *   <li>在线状态 {@code status} 不影响是否出现，只影响报文 {@code Status} 字段</li>
 *   <li>发布行指向的本地对象已不存在 → 丢弃并告警：留着只会组装出一条挂不住的孤儿节点</li>
 * </ul>
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadePublishedAssetResolver {

    private final CascadePublishManager cascadePublishManager;
    private final DeviceChannelManager deviceChannelManager;
    private final DeviceManager        deviceManager;
    private final CascadeOrgService    cascadeOrgService;

    /**
     * 某平台目录里会出现的通道（已按发布行过滤，未做父节点可见性判断）。
     *
     * @param platformId 上级平台编码
     * @return 稳定排序的通道视图；没有发布行时返回空列表
     */
    public List<CascadeChannelDTO> listChannels(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        return project(cascadePublishManager.listByPlatform(platformId,
            PublishObjectType.CHANNEL.name(), true), true);
    }

    /**
     * 把一批通道发布行投影成通道视图，<b>不看启停</b>。
     *
     * <p>页面列表用它：停用的行也要能编辑、能恢复。"会不会出现在上级目录"由调用方另行判断。
     *
     * @param rows 通道发布行
     * @return 稳定排序的通道视图；本地对象已不存在的行会被跳过
     */
    public List<CascadeChannelDTO> projectChannels(List<CascadePublishDTO> rows) {
        return project(rows == null ? new ArrayList<>() : rows, false);
    }

    /**
     * 按业务键取一条通道视图，<b>不看启停</b>。
     *
     * <p>上级点播、录像查询、PTZ 控制都靠它反查本地通道：停用只表示"不再新上报"，
     * 进行中的点播不应该被一句话掐断。
     *
     * @param objectKey {@code deviceId:channelId}
     * @return 通道视图；发布行或本地通道不存在时返回 null
     */
    public CascadeChannelDTO findChannel(String platformId, String objectKey) {
        if (StringUtils.isBlank(platformId) || StringUtils.isBlank(objectKey)) {
            return null;
        }
        CascadePublishDTO publish = cascadePublishManager.get(platformId, PublishObjectType.CHANNEL.name(), objectKey);
        return publish == null ? null : first(project(List.of(publish), false));
    }

    /**
     * 按发布行主键取一条通道视图（不看启停）：页面详情要能打开已停用的行。
     *
     * @param publishId 发布行主键
     * @return 通道视图；不存在或不是通道发布行时返回 null
     */
    public CascadeChannelDTO getChannel(Long publishId) {
        if (publishId == null) {
            return null;
        }
        CascadePublishDTO publish = cascadePublishManager.getById(publishId);
        if (publish == null || !PublishObjectType.CHANNEL.name().equals(publish.getObjectType())) {
            return null;
        }
        return first(project(List.of(publish), false));
    }

    /**
     * 某平台目录里会出现的设备节点（只在平台开了设备层时由组装器调用）。
     *
     * <p>父节点（组织）是否可见不在这里判断：那是 {@code CascadeOrgManager#platformOrgView} 的职责，
     * 组装器把两份视图叠起来才知道设备能不能立起来。
     *
     * @param platformId 上级平台编码
     * @return 稳定排序的设备视图；没有发布行时返回空列表
     */
    public List<CascadePlatformDeviceDTO> listDevices(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return new ArrayList<>();
        }
        List<CascadePublishDTO> rows = cascadePublishManager.listByPlatform(platformId,
            PublishObjectType.DEVICE.name(), true);
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> deviceIds = new LinkedHashSet<>();
        for (CascadePublishDTO publish : rows) {
            if (StringUtils.isNotBlank(publish.getObjectKey())) {
                deviceIds.add(publish.getObjectKey());
            }
        }
        Map<String, DeviceDO> deviceById = deviceManager.mapByDeviceIds(deviceIds);
        Map<Long, String> orgCodeById = orgCodeById();

        List<CascadePlatformDeviceDTO> devices = new ArrayList<>(rows.size());
        for (CascadePublishDTO publish : rows) {
            String deviceId = publish.getObjectKey();
            DeviceDO device = deviceById.get(deviceId);
            if (device == null) {
                log.warn("发布行指向的本地设备已不存在，跳过: platformId={}, deviceId={}", platformId, deviceId);
                continue;
            }
            if (!enabled(device.getEnabled())) {
                continue;
            }
            CascadePlatformDeviceDTO view = new CascadePlatformDeviceDTO();
            view.setLocalDeviceId(deviceId);
            view.setPlatformCode(CascadeCodeBuilder.effectiveCode(publish.getPublishCode(), deviceId));
            view.setName(StringUtils.defaultIfBlank(publish.getPublishName(), device.getName()));
            view.setLocalOrgCode(device.getOrgId() == null ? null : orgCodeById.get(device.getOrgId()));
            view.setSortOrder(publish.getSortOrder() == null ? 0 : publish.getSortOrder());
            view.setStatus(device.getStatus());
            devices.add(view);
        }
        devices.sort(Comparator.comparing((CascadePlatformDeviceDTO d) -> d.getSortOrder() == null ? 0 : d.getSortOrder())
            .thenComparing(d -> StringUtils.defaultString(d.getPlatformCode())));
        return devices;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 发布行 → 通道视图的统一投影。
     *
     * <p>三次批量回表（通道、设备、组织编码），不随行数增长——百路目录也只查三次。
     *
     * @param rows           通道发布行
     * @param requireEnabled 是否跳过停用的发布行/本地通道/设备；页面列表传 {@code false}
     */
    private List<CascadeChannelDTO> project(List<CascadePublishDTO> rows, boolean requireEnabled) {
        List<CascadeChannelDTO> channels = new ArrayList<>(rows.size());
        if (rows.isEmpty()) {
            return channels;
        }
        /* 先把业务键拆成 (设备, 通道)，同时收集设备集合供一次批量回表 */
        List<ChannelRow> parsed = new ArrayList<>(rows.size());
        Set<String> deviceIds = new LinkedHashSet<>();
        for (CascadePublishDTO publish : rows) {
            String deviceId = PublishKeys.channelDeviceId(publish.getObjectKey());
            String channelId = PublishKeys.channelChannelId(publish.getObjectKey());
            if (deviceId == null || channelId == null) {
                log.warn("发布行的通道业务键无法解析，跳过: platformId={}, objectKey={}",
                    publish.getPlatformId(), publish.getObjectKey());
                continue;
            }
            parsed.add(new ChannelRow(deviceId, channelId, publish));
            deviceIds.add(deviceId);
        }
        if (parsed.isEmpty()) {
            return channels;
        }

        Map<String, DeviceChannelDO> channelByKey = indexChannels(deviceIds);
        Map<String, DeviceDO> deviceById = deviceManager.mapByDeviceIds(deviceIds);
        Map<Long, String> orgCodeById = orgCodeById();
        for (ChannelRow row : parsed) {
            DeviceChannelDO local = channelByKey.get(
                DeviceChannelManager.channelKey(row.deviceId(), row.channelId()));
            if (local == null) {
                log.warn("发布行指向的本地通道已不存在，跳过: platformId={}, deviceId={}, channelId={}",
                    row.publish().getPlatformId(), row.deviceId(), row.channelId());
                continue;
            }
            DeviceDO device = deviceById.get(row.deviceId());
            if (device == null) {
                log.warn("发布行指向的本地设备已不存在，跳过: platformId={}, deviceId={}",
                    row.publish().getPlatformId(), row.deviceId());
                continue;
            }
            if (requireEnabled && (!enabled(row.publish().getEnabled()) || !enabled(local.getEnabled())
                || !enabled(device.getEnabled()))) {
                /* 发布停用只影响该平台；本地通道/设备停用影响所有平台，通道是设备的子资源 */
                continue;
            }
            channels.add(toChannelDTO(row, local, device, orgCodeById));
        }
        /* 同组织内按通道编码排序：顺序抖动会让上级反复重建节点 */
        channels.sort(Comparator.comparing((CascadeChannelDTO c) -> StringUtils.defaultString(c.getCascadeOrgId()))
            .thenComparing(c -> StringUtils.defaultString(c.getCascadeChannelId())));
        return channels;
    }

    /** 单条投影只会返回 0 或 1 个元素。 */
    private CascadeChannelDTO first(List<CascadeChannelDTO> channels) {
        return channels.isEmpty() ? null : channels.get(0);
    }

    private CascadeChannelDTO toChannelDTO(ChannelRow row, DeviceChannelDO local, DeviceDO device,
        Map<Long, String> orgCodeById) {
        CascadePublishDTO publish = row.publish();
        CascadeChannelDTO dto = new CascadeChannelDTO();
        dto.setId(publish.getId());
        dto.setCreateTime(publish.getCreateTime());
        dto.setUpdateTime(publish.getUpdateTime());
        dto.setPlatformId(publish.getPlatformId());
        dto.setLocalDeviceId(row.deviceId());
        dto.setLocalChannelId(row.channelId());
        dto.setCascadeChannelId(CascadeCodeBuilder.effectiveCode(publish.getPublishCode(), row.channelId()));
        dto.setCascadeName(StringUtils.defaultIfBlank(publish.getPublishName(), local.getName()));
        /* 通道自己的归属优先；为空表示跟随设备，由组装器决定挂设备还是挂设备的组织。
           发布行的 parent_key 不再参与：目录结构只由本地归属一个真相推导 */
        dto.setCascadeOrgId(local.getOrgId() == null ? null : orgCodeById.get(local.getOrgId()));
        dto.setDeviceOrgCode(device.getOrgId() == null ? null : orgCodeById.get(device.getOrgId()));
        dto.setEnabled(publish.getEnabled() == null ? 1 : publish.getEnabled());
        dto.setManufacturer(publish.getManufacturer());
        dto.setModel(publish.getModel());
        dto.setAddress(publish.getAddress());
        dto.setIpAddress(publish.getIpAddress());
        dto.setPtzType(publish.getPtzType());
        return dto;
    }

    private Map<String, DeviceChannelDO> indexChannels(Set<String> deviceIds) {
        Map<String, DeviceChannelDO> index = new HashMap<>();
        for (DeviceChannelDO channel : deviceChannelManager.listByDeviceIds(deviceIds)) {
            index.put(DeviceChannelManager.channelKey(channel.getDeviceId(), channel.getChannelId()), channel);
        }
        return index;
    }

    /** 组织 id → 编码。组织表是几十行量级，一次全量取比按 id 一个个查更省。 */
    private Map<Long, String> orgCodeById() {
        Map<Long, String> index = new HashMap<>();
        for (CascadeOrgDO org : cascadeOrgService.list()) {
            if (org.getId() != null && StringUtils.isNotBlank(org.getOrgCode())) {
                index.put(org.getId(), org.getOrgCode());
            }
        }
        return index;
    }

    private boolean enabled(Integer enabled) {
        return enabled != null && enabled == 1;
    }

    /** 一条通道发布行拆出来的本地标识与发布配置。 */
    private record ChannelRow(String deviceId, String channelId, CascadePublishDTO publish) {
    }
}
