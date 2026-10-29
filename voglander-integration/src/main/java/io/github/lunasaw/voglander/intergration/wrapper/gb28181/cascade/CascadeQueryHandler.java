package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import io.github.lunasaw.gb28181.common.entity.query.DeviceQuery;
import io.github.lunasaw.gb28181.common.entity.query.DeviceRecordQuery;
import io.github.lunasaw.gb28181.common.entity.query.DeviceConfigDownload;
import io.github.lunasaw.gb28181.common.entity.query.MobilePositionQuery;
import io.github.lunasaw.gb28181.common.entity.query.PresetQuery;
import io.github.lunasaw.gb28181.common.entity.notify.MobilePositionNotify;
import io.github.lunasaw.gb28181.common.entity.response.*;
import io.github.lunasaw.gb28181.common.entity.enums.CmdTypeEnum;
import io.github.lunasaw.gbproxy.client.api.QueryListener;
import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.voglander.common.constant.device.DeviceConstant;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.record.VoglanderServerRecordCommand;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeRecordRequestManager;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stage 3 — 响应上级平台的目录/信息/状态查询。
 * <p>
 * 框架要求此 Bean 唯一（{@code ObjectProvider#getIfUnique()}）。
 * Lab 模式（{@code voglander.protocol-lab.enabled=true}）时由 LabQueryListener 替代，
 * 本 Bean 不注册，避免唯一约束冲突。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnExpression(
    "${sip.client.enabled:false} and not ${voglander.protocol-lab.enabled:false}"
)
@RequiredArgsConstructor
public class CascadeQueryHandler implements QueryListener {

    private final CascadeChannelManager  cascadeChannelManager;
    private final CascadePlatformManager cascadePlatformManager;
    private final CascadeRecordRequestManager cascadeRecordRequestManager;
    private final VoglanderServerRecordCommand serverRecordCommand;
    private final CascadeDeviceSupplier  cascadeDeviceSupplier;
    private final DeviceChannelManager   deviceChannelManager;

    /**
     * 单条目录 MESSAGE 容纳的通道数上限。
     *
     * <p>上级（海康 iSecure Center 等）对单条 SIP MESSAGE 报文长度有限制，通道多时整包下发会被
     * 丢弃或截断，表现为"只有前几十个通道"。超过本值即分片下发。
     */
    @Value("${gateway.cascade.catalog.batch-size:50}")
    private int catalogBatchSize = 50;

    /** 目录查询：返回本平台对上级暴露的通道列表（超过批次大小时分多条 MESSAGE 下发） */
    @Override
    public DeviceResponse onCatalogQuery(String platformId, DeviceQuery query) {
        List<CascadeChannelDTO> channels = cascadeChannelManager.listByPlatformId(platformId);
        List<CascadeChannelDTO> exposed = channels.stream()
            .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
            .collect(Collectors.toList());
        Map<String, Integer> statusMap = loadChannelStatus(exposed);
        List<DeviceItem> items = exposed.stream()
            .map(c -> toDeviceItem(c, statusMap))
            .collect(Collectors.toList());

        DeviceResponse resp = new DeviceResponse(CmdTypeEnum.CATALOG.getType(), query.getSn(), query.getDeviceId());
        /* SumNum 必须等于本次实际下发条数：库里总行数含未启用项，按它填会让上级一直等凑不满的分片 */
        resp.setSumNum(items.size());
        resp.setDeviceItemList(items);
        log.info("响应目录查询: platformId={}, channelCount={}, batchSize={}",
            platformId, items.size(), catalogBatchSize);

        if (items.size() > catalogBatchSize) {
            /* 分片下发由本类自己完成并返回 null，框架的 ClientListenerAdapter 便不再整包回一份 */
            return sendCatalogBatched(platformId, resp);
        }
        return resp;
    }

    /**
     * 按 {@link #catalogBatchSize} 分片下发目录应答。
     *
     * @return 分片成功返回 null（阻止框架再整包下发）；失败返回原响应，由框架兜底整包下发
     */
    private DeviceResponse sendCatalogBatched(String platformId, DeviceResponse resp) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("目录分片下发跳过，找不到平台配置，回退整包: platformId={}", platformId);
            return resp;
        }
        try {
            FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
            ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
            String firstCallId = ClientCommandSender.sendCatalogCommandBatched(from, to, resp, catalogBatchSize);
            log.info("目录分片下发完成: platformId={}, 总数={}, 批次大小={}, 首个 callId={}",
                platformId, resp.getSumNum(), catalogBatchSize, firstCallId);
            return null;
        } catch (Exception e) {
            log.error("目录分片下发失败，回退整包: platformId={}", platformId, e);
            return resp;
        }
    }

    /** 一次查出本次涉及设备下所有通道的状态，避免逐通道回表。 */
    private Map<String, Integer> loadChannelStatus(List<CascadeChannelDTO> channels) {
        Set<String> deviceIds = channels.stream()
            .map(CascadeChannelDTO::getLocalDeviceId)
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toSet());
        return deviceChannelManager.mapChannelStatus(deviceIds);
    }

    /** 设备信息查询：返回本平台基础信息 */
    @Override
    public DeviceInfo onDeviceInfoQuery(String platformId, DeviceQuery query) {
        DeviceInfo info = new DeviceInfo(CmdTypeEnum.DEVICE_INFO.getType(), query.getSn(), query.getDeviceId());
        info.setManufacturer("Voglander");
        info.setModel("CascadePlatform");
        info.setFirmware("v1.0.4");
        return info;
    }

    /** 设备状态查询：平台自身始终在线 */
    @Override
    public DeviceStatus onDeviceStatusQuery(String platformId, DeviceQuery query) {
        DeviceStatus status = new DeviceStatus(CmdTypeEnum.DEVICE_STATUS.getType(), query.getSn(), query.getDeviceId());
        status.setOnline("ONLINE");
        return status;
    }

    private DeviceItem toDeviceItem(CascadeChannelDTO channel, Map<String, Integer> statusMap) {
        DeviceItem item = new DeviceItem();
        item.setDeviceId(channel.getCascadeChannelId());
        item.setName(channel.getCascadeName() != null ? channel.getCascadeName() : channel.getLocalChannelId());
        /* 真实通道离线时上级必须看到 OFF：一律报 ON 会让点播打到已掉线的设备上，等到超时才失败 */
        Integer status = statusMap.get(
            DeviceChannelManager.channelKey(channel.getLocalDeviceId(), channel.getLocalChannelId()));
        item.setStatus(status != null && status == DeviceConstant.Status.ONLINE ? "ON" : "OFF");
        item.setParental(0);
        item.setRegisterWay(1);
        item.setSafetyWay(0);
        return item;
    }

    /**
     * 录像查询（C6）：上级查录像 → 登记请求到 tb_cascade_record_request →
     * 转查真实下级设备 → 真实设备响应到达后由 {@link CascadeRecordService} 主动回包上级。
     */
    @Override
    public DeviceRecord onRecordInfoQuery(String platformId, DeviceRecordQuery query) {
        String cascadeChannelId = query.getDeviceId();
        CascadeChannelDTO channel = cascadeChannelManager.getByPlatformAndCascadeChannelId(platformId, cascadeChannelId);
        if (channel == null) {
            log.warn("录像查询找不到级联通道: platformId={}, cascadeChannelId={}", platformId, cascadeChannelId);
            return null; // 框架会自动回 404
        }
        // 登记上级查询请求（携带上级 sn，供响应时重映射）
        cascadeRecordRequestManager.create(platformId, query.getSn(), channel,
            query.getStartTime(), query.getEndTime());
        // 转查真实下级设备（异步，响应到达后 CascadeRecordService 监听事件并主动回包上级）
        serverRecordCommand.queryDeviceRecord(channel.getLocalDeviceId(), query.getStartTime(), query.getEndTime());
        log.info("录像查询已转发: platformId={}, cascadeChannelId={} → localDeviceId={}",
            platformId, cascadeChannelId, channel.getLocalDeviceId());
        // 返回 null 表示异步响应，框架不立即回包（等待 CascadeRecordService 主动推送）
        return null;
    }

    /**
     * 配置下载查询（C11）：上级查本平台配置参数 → 返回基础 OK 应答（平台自身无下级设备配置）。
     */
    @Override
    public DeviceConfigResponse onConfigDownloadQuery(String platformId, DeviceConfigDownload query) {
        log.info("响应配置下载查询: platformId={}, configType={}", platformId, query.getConfigType());
        return new DeviceConfigResponse(query.getSn(), query.getDeviceId(), "OK");
    }

    /**
     * 移动位置查询（C11）：级联平台自身非移动设备，返回 null（框架回 404/不回包），
     * 由上级订阅真实移动设备位置走 {@link CascadeNotifyPublisher} 主动推送。
     */
    @Override
    public MobilePositionNotify onMobilePositionQuery(String platformId, MobilePositionQuery query) {
        log.info("收到移动位置查询(平台自身无位置, 不应答): platformId={}", platformId);
        return null;
    }

    /**
     * 预置位查询（C11）：上级查通道预置位 → 首版返回空列表（预置位由真实设备维护，后续可转查下级）。
     */
    @Override
    public PresetQueryResponse onPresetQuery(String platformId, PresetQuery query) {
        log.info("响应预置位查询(首版空列表): platformId={}, channelId={}", platformId, query.getDeviceId());
        PresetQueryResponse.PresetList presetList =
            new PresetQueryResponse.PresetList(0, java.util.Collections.emptyList());
        return new PresetQueryResponse(query.getSn(), query.getDeviceId(), presetList);
    }
}
