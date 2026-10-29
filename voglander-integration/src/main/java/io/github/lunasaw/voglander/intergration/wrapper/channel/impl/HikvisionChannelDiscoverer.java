package io.github.lunasaw.voglander.intergration.wrapper.channel.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.intergration.wrapper.channel.ChannelDiscoverer;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoverResult;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoveredChannel;
import io.github.lunasaw.voglander.intergration.wrapper.channel.support.ChannelEndpoint;
import io.github.lunasaw.voglander.intergration.wrapper.channel.support.HttpDigestClient;
import io.github.lunasaw.voglander.intergration.wrapper.channel.support.IsapiXmlParser;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import lombok.extern.slf4j.Slf4j;

/**
 * 海康通道发现器：读 ISAPI 的输入代理（InputProxy）台账。
 *
 * <p>
 * 海康 NVR 把自己代理接入的每一路 IPC 都登记在 {@code /ISAPI/ContentMgmt/InputProxy/channels} 里，
 * 每条记录的 {@code <id>} 恰好就是 RTSP 路径里的通道号——这是本实现能"发现即可点播"的关键：
 * 台账 id 与 {@code /Streaming/Channels/<id>01} 一一对应，不需要任何额外映射。
 * </p>
 *
 * <p>
 * 顺序上有讲究：先读 {@code deviceInfo} 确认 {@code manufacturer=hikvision} 再读台账。
 * 对着一台大华或路由器发 ISAPI 请求，返回的可能是 200 的登录页或 404 的 HTML，
 * 不校验厂商就会把"页面文本"当台账解析，最后报一个莫名其妙的空通道。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Slf4j
@Component
public class HikvisionChannelDiscoverer implements ChannelDiscoverer {

    /** 厂商标识 */
    public static final String VENDOR = "hikvision";

    /** 设备信息（用于确认厂商与型号） */
    private static final String PATH_DEVICE_INFO = "/ISAPI/System/deviceInfo";

    /** 输入代理通道台账 */
    private static final String PATH_CHANNELS = "/ISAPI/ContentMgmt/InputProxy/channels";

    /** 通道在线状态 */
    private static final String PATH_CHANNEL_STATUS = "/ISAPI/ContentMgmt/InputProxy/channels/status";

    /** 海康 RTSP 路径：{@code /Streaming/Channels/} + 通道号 + 两位码流号 */
    private static final String RTSP_PATH_PREFIX = "/Streaming/Channels/";

    /** 主码流后缀 */
    private static final String SUFFIX_MAIN = "01";

    /** 辅码流后缀 */
    private static final String SUFFIX_SUB = "02";

    @Autowired
    private HttpDigestClient httpDigestClient;

    @Override
    public String vendor() {
        return VENDOR;
    }

    /**
     * 只要能定位到设备管理面就认为"可以试一次"：真有网络或凭据问题时，
     * {@link #discover} 会给出比"没有适配器"更具体的失败原因。
     */
    @Override
    public boolean supports(DeviceDTO device) {
        ChannelEndpoint endpoint = ChannelEndpoint.of(device);
        return endpoint != null && endpoint.hasHost();
    }

    @Override
    public DiscoverResult discover(DeviceDTO device, int timeoutMs) {
        ChannelEndpoint endpoint = ChannelEndpoint.of(device);
        if (endpoint == null || !endpoint.hasHost()) {
            return DiscoverResult.failure(VENDOR, "设备未配置拉流地址或 IP，无法定位管理面");
        }
        if (!endpoint.hasCredential()) {
            return DiscoverResult.failure(VENDOR, "拉流地址里没有账号密码，无法读取通道清单");
        }

        String manufacturer;
        String model;
        String firmware;
        try {
            HttpDigestClient.Response info = httpDigestClient.get(endpoint.httpUrl(PATH_DEVICE_INFO),
                endpoint.getUser(), endpoint.getPassword(), timeoutMs);
            if (!info.isSuccess()) {
                return DiscoverResult.failure(VENDOR, describeFailure("读取设备信息", endpoint, info));
            }
            manufacturer = IsapiXmlParser.deviceInfoField(info.getBody(), "manufacturer");
            model = IsapiXmlParser.deviceInfoField(info.getBody(), "model");
            firmware = IsapiXmlParser.deviceInfoField(info.getBody(), "firmwareVersion");
        } catch (IllegalArgumentException e) {
            return DiscoverResult.failure(VENDOR, e.getMessage());
        }

        if (manufacturer == null || !manufacturer.equalsIgnoreCase(VENDOR)) {
            return DiscoverResult.failure(VENDOR, "设备厂商为 " + manufacturer + "，不是海康，暂不支持通道发现");
        }

        List<IsapiXmlParser.IsapiChannel> rawChannels;
        try {
            HttpDigestClient.Response channelResp = httpDigestClient.get(endpoint.httpUrl(PATH_CHANNELS),
                endpoint.getUser(), endpoint.getPassword(), timeoutMs);
            if (!channelResp.isSuccess()) {
                return DiscoverResult.failure(VENDOR, describeFailure("读取通道清单", endpoint, channelResp));
            }
            rawChannels = IsapiXmlParser.parseChannels(channelResp.getBody());
        } catch (IllegalArgumentException e) {
            return DiscoverResult.failure(VENDOR, e.getMessage());
        }

        Map<String, Integer> statusMap = queryStatus(endpoint, timeoutMs);

        List<DiscoveredChannel> channels = new ArrayList<>(rawChannels.size());
        for (IsapiXmlParser.IsapiChannel raw : rawChannels) {
            channels.add(toDiscovered(raw, statusMap.get(raw.channelId()), endpoint));
        }

        DiscoverResult result = DiscoverResult.success(VENDOR, channels);
        result.setDeviceModel(model);
        result.setFirmwareVersion(firmware);
        log.info("[hikvision.discover] host={} model={} 通道数={} 在线={}", endpoint.getHost(), model,
            channels.size(), channels.stream().filter(c -> c.getStatus() != null && c.getStatus() == 1).count());
        return result;
    }

    /**
     * 通道在线态：查失败时返回空 Map（通道清单照常返回，status 留空即"未知"）。
     * 台账是主体，状态是修饰——不能因为状态接口抖动就让整次发现失败。
     */
    private Map<String, Integer> queryStatus(ChannelEndpoint endpoint, int timeoutMs) {
        try {
            HttpDigestClient.Response resp = httpDigestClient.get(endpoint.httpUrl(PATH_CHANNEL_STATUS),
                endpoint.getUser(), endpoint.getPassword(), timeoutMs);
            if (!resp.isSuccess()) {
                log.warn("[hikvision.discover] 通道状态查询失败 host={} reason={}", endpoint.getHost(),
                    resp.getMessage());
                return Map.of();
            }
            return IsapiXmlParser.parseChannelStatus(resp.getBody());
        } catch (Exception e) {
            log.warn("[hikvision.discover] 通道状态解析异常 host={}: {}", endpoint.getHost(), e.getMessage());
            return Map.of();
        }
    }

    private DiscoveredChannel toDiscovered(IsapiXmlParser.IsapiChannel raw, Integer status,
                                           ChannelEndpoint endpoint) {
        DiscoveredChannel channel = new DiscoveredChannel();
        channel.setChannelId(raw.channelId());
        channel.setName(raw.name());
        channel.setStatus(status);
        channel.setRtspUrl(endpoint.rtspUrl(rtspPath(raw.channelId(), false)));
        channel.setRtspSubUrl(endpoint.rtspUrl(rtspPath(raw.channelId(), true)));
        channel.setProxyProtocol(raw.proxyProtocol());
        channel.setSourceIp(raw.sourceIp());
        channel.setSourcePort(raw.sourcePort());
        channel.setSourceChannel(raw.sourceChannel());
        channel.setSourceModel(raw.sourceModel());
        channel.setSourceFirmware(raw.sourceFirmware());
        return channel;
    }

    /**
     * 海康 RTSP 路径：通道号直接拼两位码流号（通道 3 → {@code 301} / {@code 302}）。
     * 通道号必须用台账里的 id 而非集合下标——NVR 删过通道后编号会留空位。
     */
    private String rtspPath(String channelId, boolean subProfile) {
        return RTSP_PATH_PREFIX + channelId + (subProfile ? SUFFIX_SUB : SUFFIX_MAIN);
    }

    private String describeFailure(String action, ChannelEndpoint endpoint, HttpDigestClient.Response resp) {
        String reason = resp.getMessage() == null ? "HTTP " + resp.getStatusCode() : resp.getMessage();
        return action + "失败（" + endpoint.getHost() + ":" + endpoint.getHttpPort() + "）：" + reason;
    }
}
