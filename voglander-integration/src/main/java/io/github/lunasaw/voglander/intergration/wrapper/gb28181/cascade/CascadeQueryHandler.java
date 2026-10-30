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
import io.github.lunasaw.sip.common.context.SipTransactionContext;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.device.VoglanderServerDeviceCommand;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.record.VoglanderServerRecordCommand;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeRecordRequestManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 3 — 响应上级平台的目录/信息/状态/配置/预置位查询。
 * <p>
 * 框架要求此 Bean 唯一（{@code ObjectProvider#getIfUnique()}）。
 * Lab 模式（{@code voglander.protocol-lab.enabled=true}）时由 LabQueryListener 替代，
 * 本 Bean 不注册，避免唯一约束冲突。
 * </p>
 *
 * <p><b>为什么几乎所有应答都「自己发」并 {@code return null}</b>：框架的
 * {@code ClientListenerAdapter} 回包时把 From 写成 {@code sip.client.clientId}（设备侧客户端编码），
 * 而上级认的是 {@code tb_cascade_platform.local_client_id}（本端平台编码）。身份不符的应答会被上级丢弃，
 * 表现为「查询有回包、上级仍报超时/目录为空」。目录应答早就改成自发送，这里把其余查询应答统一收口到
 * {@link CascadeUpperMessenger}，两条路径不再分叉。
 * </p>
 *
 * <p>平台配置缺失（找不到平台 / {@code localIp} 未回填）时仍把实体交回框架兜底：
 * 至少能发出一包，便于现场定位是「配置没填」还是「协议不对」。</p>
 *
 * @author luna
 */
@Slf4j
@Component
@ConditionalOnExpression(
    "${sip.client.enabled:false} and not ${voglander.protocol-lab.enabled:false}"
)
@RequiredArgsConstructor
public class CascadeQueryHandler implements QueryListener {

    private final CascadeChannelManager         cascadeChannelManager;
    private final CascadePlatformManager        cascadePlatformManager;
    private final CascadeRecordRequestManager   cascadeRecordRequestManager;
    private final VoglanderServerRecordCommand  serverRecordCommand;
    private final VoglanderServerDeviceCommand  serverDeviceCommand;
    private final CascadeCatalogAssembler       catalogAssembler;
    private final CascadeCatalogPager           catalogPager;
    private final CascadeUpperMessenger         upperMessenger;
    private final DeviceManager                 deviceManager;

    /** 上级资产台账里显示的本端平台名称（不配则用平台编码）。 */
    @Value("${gateway.cascade.device-info.name:}")
    private String deviceInfoName = "";

    @Value("${gateway.cascade.device-info.manufacturer:Voglander}")
    private String deviceManufacturer = "Voglander";

    @Value("${gateway.cascade.device-info.model:CascadePlatform}")
    private String deviceModel = "CascadePlatform";

    @Value("${gateway.cascade.device-info.firmware:1.10.1}")
    private String deviceFirmware = "1.10.1";

    /** 国标 DeviceStatus/BasicParam 的心跳超时次数（缺省 3 次，与常见设备一致）。 */
    @Value("${gateway.cascade.device-info.heartbeat-count:3}")
    private int heartbeatCount = 3;

    /** 目录查询：返回本平台对上级暴露的完整目录树（域 → 组织 → 通道） */
    @Override
    public DeviceResponse onCatalogQuery(String platformId, DeviceQuery query) {
        /* 节点组装统一走 Assembler：查询应答与订阅推送必须产出同一套节点，否则两条路径会长期漂移 */
        List<CascadeCatalogNode> nodes = catalogAssembler.assemble(platformId, query.getDeviceId());
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null || StringUtils.isBlank(platform.getLocalIp())) {
            /* 平台未回填 localIp 时自行下发会在 Via/Contact 里带空地址，上级根本收不到，不如交回框架 */
            log.warn("目录下发回退框架默认整包: platformId={}, 平台配置={}, localIp={}",
                platformId, platform == null ? "缺失" : "存在", platform == null ? null : platform.getLocalIp());
            int sumNum = Math.max(nodes.size(), 1);
            return catalogResponse(query, sumNum, catalogAssembler.toDeviceItems(nodes));
        }
        /* 应答必须沿用上级请求的 Call-ID，否则上级认不出这是它那条查询的应答 */
        String callId = SipTransactionContext.getCurrentCallId();
        int sent = catalogPager.sendCatalog(platform, callId, query.getDeviceId(), query.getSn(), nodes);
        log.info("响应目录查询: platformId={}, 总节点={}, 已发 {} 包（串行时其余分包按 200 OK 推进）",
            platformId, nodes.size(), sent);

        /* 不把 resp 交回框架：身份不符的应答会被上级丢弃，统一由 Pager 按平台身份下发 */
        return null;
    }

    /** 设备信息查询（§9.5.3.2）：返回本平台基础信息 + 视频输入通道数 */
    @Override
    public DeviceInfo onDeviceInfoQuery(String platformId, DeviceQuery query) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        String xml = CascadeManscdpXml.deviceInfoResponse(new CascadeManscdpXml.DeviceInfoView(
            query.getDeviceId(), query.getSn(), deviceName(platform), "OK",
            deviceManufacturer, deviceModel, deviceFirmware, countEnabledChannels(platformId)));
        if (sendAsPlatform(platform, xml)) {
            return null;
        }
        /* 兜底：平台配置缺失时至少回一包，避免上级完全查不到本端 */
        DeviceInfo info = new DeviceInfo(CmdTypeEnum.DEVICE_INFO.getType(), query.getSn(), query.getDeviceId());
        info.setDeviceName(deviceName(platform));
        info.setResult("OK");
        info.setManufacturer(deviceManufacturer);
        info.setModel(deviceModel);
        info.setFirmware(deviceFirmware);
        info.setChannel(countEnabledChannels(platformId));
        return info;
    }

    /** 设备状态查询（§9.5.3.3）：平台自身始终在线，并带上国标要求的 DeviceTime */
    @Override
    public DeviceStatus onDeviceStatusQuery(String platformId, DeviceQuery query) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        String xml = CascadeManscdpXml.deviceStatusResponse(new CascadeManscdpXml.DeviceStatusView(
            query.getDeviceId(), query.getSn(), "OK", true, "ON", "OFF", null));
        if (sendAsPlatform(platform, xml)) {
            return null;
        }
        DeviceStatus status = new DeviceStatus(CmdTypeEnum.DEVICE_STATUS.getType(), query.getSn(), query.getDeviceId());
        status.setOnline("ONLINE");
        return status;
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
     * 配置下载查询（§9.5.3.4）：返回本平台的基础参数（名称/注册有效期/心跳）。
     *
     * <p>只带 {@code BasicParam}：编码参数、录像计划等属于真实设备，由设备侧查询链路负责，
     * 在这里臆造一份会让上级台账与本端实际配置长期不一致。
     */
    @Override
    public DeviceConfigResponse onConfigDownloadQuery(String platformId, DeviceConfigDownload query) {
        log.info("响应配置下载查询: platformId={}, configType={}", platformId, query.getConfigType());
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        CascadeManscdpXml.BasicParamView basicParam = new CascadeManscdpXml.BasicParamView(
            deviceName(platform),
            platform == null ? null : platform.getRegisterExpires(),
            platform == null ? null : platform.getKeepaliveInterval(),
            heartbeatCount);
        String xml = CascadeManscdpXml.configDownloadResponse(query.getDeviceId(), query.getSn(), basicParam);
        if (sendAsPlatform(platform, xml)) {
            return null;
        }
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
     * 预置位查询（§9.5.3.5）：读真实设备应答的缓存快照。
     *
     * <p>预置位由真实设备维护，本端通过 {@code serverDeviceCommand.queryDevicePreset} 主动查询后，
     * 入站应答被 {@code Gb28181ProtocolHandler#handlePreset} 序列化写入
     * {@code device.extend.presets}。这里直接读缓存并重映射成上级编码，避免每次查询都同步等设备回包
     * （设备离线时上级会卡到超时）。
     *
     * <p>缓存为空时顺带触发一次异步刷新，本次仍回 {@code Num=0} 的空列表：让上级立刻拿到应答，
     * 下一次查询就能命中新缓存。
     */
    @Override
    public PresetQueryResponse onPresetQuery(String platformId, PresetQuery query) {
        String cascadeChannelId = query.getDeviceId();
        CascadeChannelDTO channel = cascadeChannelManager.getByPlatformAndCascadeChannelId(platformId, cascadeChannelId);
        List<CascadeManscdpXml.PresetItemView> items = new ArrayList<>();
        if (channel == null) {
            log.warn("预置位查询找不到级联通道: platformId={}, cascadeChannelId={}", platformId, cascadeChannelId);
        } else {
            items = cachedPresets(channel);
            if (items.isEmpty()) {
                refreshPresets(channel.getLocalDeviceId());
            }
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        String xml = CascadeManscdpXml.presetQueryResponse(cascadeChannelId, query.getSn(), items);
        if (sendAsPlatform(platform, xml)) {
            return null;
        }
        List<PresetQueryResponse.PresetItem> fallback = new ArrayList<>();
        for (CascadeManscdpXml.PresetItemView item : items) {
            fallback.add(new PresetQueryResponse.PresetItem(item.presetId(), item.presetName()));
        }
        return new PresetQueryResponse(query.getSn(), cascadeChannelId,
            new PresetQueryResponse.PresetList(fallback.size(), fallback));
    }

    /**
     * 读设备缓存里的预置位快照。
     *
     * <p>缓存粒度是<b>设备级</b>（{@code extend.presets} 存在设备上，不区分通道）：GB28181 设备的
     * 预置位多数按通道维护，当前实现取设备快照可满足上级面板展示，通道级精确化留作后续。
     */
    private List<CascadeManscdpXml.PresetItemView> cachedPresets(CascadeChannelDTO channel) {
        String localDeviceId = channel.getLocalDeviceId();
        if (StringUtils.isBlank(localDeviceId)) {
            return Collections.emptyList();
        }
        try {
            DeviceDTO device = deviceManager.getDtoByDeviceId(localDeviceId);
            if (device == null || device.getExtendInfo() == null) {
                return Collections.emptyList();
            }
            String json = device.getExtendInfo().getPresets();
            if (StringUtils.isBlank(json)) {
                return Collections.emptyList();
            }
            PresetQueryResponse cached = JSON.parseObject(json, PresetQueryResponse.class);
            if (cached == null || cached.getPresetList() == null || cached.getPresetList().getItems() == null) {
                return Collections.emptyList();
            }
            List<CascadeManscdpXml.PresetItemView> items = new ArrayList<>(cached.getPresetList().getItems().size());
            for (PresetQueryResponse.PresetItem item : cached.getPresetList().getItems()) {
                if (item == null || StringUtils.isBlank(item.getPresetId())) {
                    continue;
                }
                items.add(new CascadeManscdpXml.PresetItemView(item.getPresetId(), item.getPresetName()));
            }
            return items;
        } catch (Exception e) {
            log.warn("读取预置位缓存失败: localDeviceId={}, err={}", localDeviceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 触发一次真实设备的预置位查询（异步回填缓存，不等结果）。 */
    private void refreshPresets(String localDeviceId) {
        if (StringUtils.isBlank(localDeviceId)) {
            return;
        }
        try {
            serverDeviceCommand.queryDevicePreset(localDeviceId);
        } catch (Exception e) {
            log.warn("触发预置位查询失败: localDeviceId={}, err={}", localDeviceId, e.getMessage());
        }
    }

    /** 以平台身份发一条应答；平台配置缺失/地址不全时返回 false，由调用方回退框架。 */
    private boolean sendAsPlatform(CascadePlatformDTO platform, String xml) {
        if (platform == null) {
            log.warn("平台配置缺失，应答回退框架默认实现");
            return false;
        }
        String callId = upperMessenger.send(platform, xml, SipTransactionContext.getCurrentCallId(), null);
        return callId != null;
    }

    /** 上级台账里看到的平台名称：显式配置优先，否则用本端平台编码。 */
    private String deviceName(CascadePlatformDTO platform) {
        if (StringUtils.isNotBlank(deviceInfoName)) {
            return deviceInfoName;
        }
        return platform == null ? null : platform.getLocalClientId();
    }

    /** 视频输入通道数（§9.5.3.2 的 Channel）：按已启用级联通道计数。 */
    private int countEnabledChannels(String platformId) {
        try {
            List<CascadeChannelDTO> channels = cascadeChannelManager.listByPlatformId(platformId);
            if (channels == null) {
                return 0;
            }
            return (int) channels.stream().filter(c -> c.getEnabled() != null && c.getEnabled() == 1).count();
        } catch (Exception e) {
            log.warn("统计级联通道数失败: platformId={}, err={}", platformId, e.getMessage());
            return 0;
        }
    }

    /** 组装一包目录应答；{@code sumNum} 是本次目录的<b>总节点数</b>，不是本包条数。 */
    private DeviceResponse catalogResponse(DeviceQuery query, int sumNum, List<DeviceItem> items) {
        DeviceResponse resp = new DeviceResponse(CmdTypeEnum.CATALOG.getType(), query.getSn(), query.getDeviceId());
        resp.setSumNum(sumNum);
        resp.setDeviceItemList(items);
        return resp;
    }
}
