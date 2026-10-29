package io.github.lunasaw.voglander.service.device;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.github.lunasaw.voglander.common.enums.DeviceAgreementEnum;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.intergration.wrapper.channel.ChannelDiscoverer;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoverResult;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoveredChannel;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.service.device.dto.ChannelSyncResultDTO;
import lombok.extern.slf4j.Slf4j;

/**
 * 通道发现编排：设备侧读台账 → 转成平台通道 → 幂等落库。
 *
 * <p>
 * 分两层：厂商差异全部收在 {@link ChannelDiscoverer} 里，本类只负责"选发现器、校验设备、合并旧值、落库"。
 * 关键约定是**失败绝不落库**：发现失败时台账原样保留，{@link ChannelSyncResultDTO#isPersisted()}
 * 为 {@code false} 且带上失败原因，避免一次网络抖动把设备下所有通道清空。
 * </p>
 *
 * <p>
 * 落库复用 {@link DeviceChannelManager#batchUpsertWithStatus}：设备粒度锁、一次 SELECT 快照、
 * 与 GB28181 目录共用同一套 status/lastSeenTime 语义，不额外造一套通道写入路径。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Slf4j
@Service
public class DeviceChannelDiscoveryService {

    /** 通道状态来源：主动发现 */
    public static final String STATUS_SOURCE_DISCOVER = "DISCOVER";

    @Autowired
    private DeviceManager            deviceManager;

    @Autowired
    private DeviceChannelManager     deviceChannelManager;

    /**
     * Spring 注入容器内全部发现器；新增厂商只需再写一个 {@link ChannelDiscoverer} 实现
     */
    @Autowired
    private List<ChannelDiscoverer>  discoverers;

    @Value("${voglander.channel-discovery.timeout-ms:3000}")
    private int                      defaultTimeoutMs = ChannelDiscoverer.DEFAULT_TIMEOUT_MS;

    /**
     * 同步设备通道（默认超时）。
     *
     * @param deviceId 设备 ID
     * @return 同步结果；发现失败时 {@code persisted=false}、{@code failureReason} 有值
     */
    public ChannelSyncResultDTO sync(String deviceId) {
        return sync(deviceId, defaultTimeoutMs);
    }

    /**
     * 同步设备通道。
     *
     * @param deviceId  设备 ID
     * @param timeoutMs 单次 HTTP 读取超时（毫秒）
     * @return 同步结果
     */
    public ChannelSyncResultDTO sync(String deviceId, int timeoutMs) {
        if (StringUtils.isBlank(deviceId)) {
            throw new ServiceException("设备ID不能为空");
        }
        DeviceDTO device = deviceManager.getDtoByDeviceId(deviceId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + deviceId);
        }
        assertDiscoverable(device);

        ChannelSyncResultDTO result = new ChannelSyncResultDTO();
        result.setDeviceId(deviceId);

        String lastFailure = "未找到可用的通道发现器";
        String lastVendor = null;
        for (ChannelDiscoverer discoverer : discoverers) {
            if (!discoverer.supports(device)) {
                continue;
            }
            DiscoverResult discovered = discoverer.discover(device, timeoutMs);
            lastVendor = discovered.getVendor() != null ? discovered.getVendor() : discoverer.vendor();
            if (discovered.isSuccess()) {
                return persist(device, discovered);
            }
            lastFailure = discovered.getFailureReason();
            log.warn("[channel.discover] 发现失败 deviceId={} vendor={} reason={}", deviceId, lastVendor, lastFailure);
        }

        result.setVendor(lastVendor);
        result.setPersisted(false);
        result.setFailureReason(lastFailure);
        return result;
    }

    /**
     * 列出设备下已落库的通道（发现前给前端看现状，发现后看结果）。
     *
     * @param deviceId 设备 ID
     * @return 通道列表；设备无通道时返回空列表
     */
    public List<DeviceChannelDTO> listByDevice(String deviceId) {
        if (StringUtils.isBlank(deviceId)) {
            return List.of();
        }
        DeviceChannelDO query = new DeviceChannelDO();
        query.setDeviceId(deviceId);
        return deviceChannelManager.listDeviceChannelDTO(query);
    }

    /**
     * GB28181 设备的通道来自目录应答（{@code handleCatalog}），主动发现只面向拉流类设备；
     * 对着国标设备发 ISAPI 请求既无意义也会制造误导性的失败提示。
     */
    private void assertDiscoverable(DeviceDTO device) {
        DeviceAgreementEnum agreement = DeviceAgreementEnum.getByType(device.getType());
        if (agreement == null) {
            throw new ServiceException("设备协议类型未知，无法同步通道: " + device.getType());
        }
        if (agreement == DeviceAgreementEnum.RTSP_IPC || agreement == DeviceAgreementEnum.ONVIF_IPC) {
            return;
        }
        throw new ServiceException("该设备协议的通道由" + agreement.getDesc() + "自身的目录机制维护，不支持主动同步");
    }

    private ChannelSyncResultDTO persist(DeviceDTO device, DiscoverResult discovered) {
        String deviceId = device.getDeviceId();
        ChannelSyncResultDTO result = new ChannelSyncResultDTO();
        result.setDeviceId(deviceId);
        result.setVendor(discovered.getVendor());
        result.setDeviceModel(discovered.getDeviceModel());
        result.setFirmwareVersion(discovered.getFirmwareVersion());
        result.setDiscovered(discovered.getChannels().size());

        if (discovered.getChannels().isEmpty()) {
            // 设备确实没有通道：无需落库，也不必凭空清理台账
            result.setOnlineCount(0);
            result.setChannels(List.of());
            return result;
        }

        Map<String, DeviceChannelDTO.ExtendInfo> existing = loadExtendInfo(deviceId);
        LocalDateTime now = LocalDateTime.now();
        List<DeviceChannelDTO> dtos = new ArrayList<>(discovered.getChannels().size());
        int onlineCount = 0;
        for (DiscoveredChannel channel : discovered.getChannels()) {
            if (channel.getStatus() != null && channel.getStatus() == 1) {
                onlineCount++;
            }
            dtos.add(toDto(deviceId, discovered.getVendor(), channel, existing.get(channel.getChannelId()), now));
        }

        int affected = deviceChannelManager.batchUpsertWithStatus(deviceId, dtos);
        result.setOnlineCount(onlineCount);
        result.setAffected(affected);
        result.setPersisted(affected > 0);
        if (affected == 0) {
            result.setFailureReason("通道清单已读到，但落库未生效（可能与其他同步并发），请重试");
        }

        // 落库后回读：预览里的编码等字段来自真实库值，而不是刚落库前的内存值
        result.setChannels(affected > 0 ? listByDevice(deviceId) : dtos);
        log.info("[channel.discover] 同步完成 deviceId={} vendor={} 通道数={} 在线={} 落库={}", deviceId,
            discovered.getVendor(), dtos.size(), onlineCount, affected);
        return result;
    }

    /**
     * 现有通道的扩展信息，按 channelId 建索引：用于把"点播成功后回填"的编码/分辨率带过来。
     * 少了这一步，每次重新同步都会把已经探测到的编码冲掉，前端又要退回"未知编码"。
     */
    private Map<String, DeviceChannelDTO.ExtendInfo> loadExtendInfo(String deviceId) {
        DeviceChannelDO query = new DeviceChannelDO();
        query.setDeviceId(deviceId);
        Map<String, DeviceChannelDTO.ExtendInfo> map = new HashMap<>();
        for (DeviceChannelDTO exist : deviceChannelManager.listDeviceChannelDTO(query)) {
            if (exist.getChannelId() != null) {
                map.put(exist.getChannelId(), exist.getExtendInfo());
            }
        }
        return map;
    }

    private DeviceChannelDTO toDto(String deviceId, String vendor, DiscoveredChannel channel,
                                   DeviceChannelDTO.ExtendInfo exist, LocalDateTime now) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setDeviceId(deviceId);
        dto.setChannelId(channel.getChannelId());
        dto.setName(StringUtils.isNotBlank(channel.getName()) ? channel.getName()
            : "通道" + channel.getChannelId());
        dto.setStatus(channel.getStatus());
        dto.setLastSeenTime(now);
        dto.setStatusSource(STATUS_SOURCE_DISCOVER);
        dto.setMissingCount(0);

        DeviceChannelDTO.ExtendInfo extendInfo = new DeviceChannelDTO.ExtendInfo();
        extendInfo.setRtspUrl(channel.getRtspUrl());
        extendInfo.setRtspSubUrl(channel.getRtspSubUrl());
        extendInfo.setVendor(vendor);
        extendInfo.setProxyProtocol(channel.getProxyProtocol());
        extendInfo.setSourceIp(channel.getSourceIp());
        extendInfo.setSourcePort(channel.getSourcePort());
        extendInfo.setSourceChannel(channel.getSourceChannel());
        extendInfo.setSourceModel(channel.getSourceModel());
        extendInfo.setSourceFirmware(channel.getSourceFirmware());
        carryOverProbeResult(extendInfo, exist);
        dto.setExtendInfo(extendInfo);
        return dto;
    }

    /**
     * 保留上次点播探测出的编码与分辨率。
     *
     * <p>
     * 只搬"探测结果"这一组字段，不搬地址：地址必须跟着本次发现的设备应答走，
     * 否则设备改端口或换凭据后通道会一直指着旧地址。
     * </p>
     */
    private void carryOverProbeResult(DeviceChannelDTO.ExtendInfo target, DeviceChannelDTO.ExtendInfo exist) {
        if (exist == null) {
            return;
        }
        if (Objects.equals(exist.getRtspUrl(), target.getRtspUrl())) {
            target.setVideoCodec(exist.getVideoCodec());
            target.setVideoWidth(exist.getVideoWidth());
            target.setVideoHeight(exist.getVideoHeight());
        }
        if (target.getVendor() == null) {
            target.setVendor(exist.getVendor());
        }
    }
}
