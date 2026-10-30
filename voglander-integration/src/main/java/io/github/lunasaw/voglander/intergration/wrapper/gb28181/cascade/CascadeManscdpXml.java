package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * 级联自建 MANSCDP 报文渲染工具（附录 A）。
 *
 * <p><b>为什么要自己拼 XML</b>：框架的 {@code ClientListenerAdapter} 回包时把 From 写成
 * {@code sip.client.clientId}（设备侧客户端身份），而上级认的是
 * {@code tb_cascade_platform.local_client_id}（本端平台编码）。身份不符的应答会被上级直接丢弃，
 * 表现为「MESSAGE 有回包、上级却报查询超时」。目录应答早已改为自发送，这里把同类应答
 * （语音广播、设备信息、设备状态、配置下载、预置位）统一收口，避免两条路径长期漂移。
 *
 * <p><b>报文外观</b>与 {@link CascadeCatalogAssembler} 保持一致：{@code \n} 换行、除首行外每行一个
 * 前导空格、声明 {@code encoding="GB2312"}、可选字段为空时整段省略（空标签会被上级当成非法节点）。
 *
 * @author luna
 */
public final class CascadeManscdpXml {

    private static final String          LF         = "\n";
    private static final String          INDENT     = " ";
    private static final String          DECLARATION = "<?xml version=\"1.0\" encoding=\"GB2312\"?>";

    /** 国标 DeviceTime 格式（§9.5.3.3 要求 {@code yyyy-MM-ddTHH:mm:ss}） */
    private static final DateTimeFormatter DEVICE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private CascadeManscdpXml() {
    }

    /**
     * 语音广播应答（A.2.6 e)）：上级 {@code Notify/Broadcast} 之后由语音流接收者回。
     *
     * <pre>{@code
     * <Response><CmdType>Broadcast</CmdType><SN>1</SN><DeviceID>target</DeviceID><Result>OK</Result></Response>
     * }</pre>
     *
     * @param deviceId 上级通知里的 TargetID（原样带回，上级按此匹配是哪台语音输出设备）
     * @param sn       上级通知里的 SN
     * @param result   {@code OK} / {@code ERROR}
     */
    public static String broadcastResponse(String deviceId, String sn, String result) {
        StringBuilder xml = start("Response");
        append(xml, "CmdType", "Broadcast");
        append(xml, "SN", sn);
        append(xml, "DeviceID", deviceId);
        append(xml, "Result", result);
        return end(xml, "Response");
    }

    /** 设备信息应答（A.2.6 f) / §9.5.3.2）。 */
    public static String deviceInfoResponse(DeviceInfoView view) {
        StringBuilder xml = start("Response");
        append(xml, "CmdType", "DeviceInfo");
        append(xml, "SN", view.sn());
        append(xml, "DeviceID", view.deviceId());
        append(xml, "DeviceName", view.deviceName());
        append(xml, "Result", StringUtils.defaultIfBlank(view.result(), "OK"));
        append(xml, "Manufacturer", view.manufacturer());
        append(xml, "Model", view.model());
        append(xml, "Firmware", view.firmware());
        /* Channel 是「视频输入通道数」，0 也有意义（纯上级平台无通道），因此不走 append 的空值省略 */
        xml.append(INDENT).append("<Channel>").append(Math.max(view.channel(), 0)).append("</Channel>").append(LF);
        return end(xml, "Response");
    }

    /**
     * 设备状态应答（A.2.6 g) / §9.5.3.3）。
     *
     * <p>{@code DeviceTime} 是国标列的必选字段之一，缺失时部分上级把应答判为不完整。
     */
    public static String deviceStatusResponse(DeviceStatusView view) {
        StringBuilder xml = start("Response");
        append(xml, "CmdType", "DeviceStatus");
        append(xml, "SN", view.sn());
        append(xml, "DeviceID", view.deviceId());
        append(xml, "Result", StringUtils.defaultIfBlank(view.result(), "OK"));
        append(xml, "Online", view.online() ? "ONLINE" : "OFFLINE");
        append(xml, "Status", "OK");
        append(xml, "Encode", StringUtils.defaultIfBlank(view.encode(), "ON"));
        append(xml, "Record", StringUtils.defaultIfBlank(view.record(), "OFF"));
        append(xml, "DeviceTime", DEVICE_TIME.format(
            view.deviceTime() != null ? view.deviceTime() : LocalDateTime.now()));
        return end(xml, "Response");
    }

    /**
     * 配置下载应答（A.2.6 j) / §9.5.3.4）。
     *
     * <p>只带 {@code BasicParam}：本平台作为上级平台代理，暴露的正是自己的注册/心跳参数，
     * 其它配置项（编码参数、录像计划等）属于真实设备，由设备侧查询链路负责，此处不臆造。
     */
    public static String configDownloadResponse(String deviceId, String sn, BasicParamView basicParam) {
        StringBuilder xml = start("Response");
        append(xml, "CmdType", "ConfigDownload");
        append(xml, "SN", sn);
        append(xml, "DeviceID", deviceId);
        append(xml, "Result", "OK");
        if (basicParam != null) {
            xml.append(INDENT).append("<BasicParam>").append(LF);
            append(xml, "Name", basicParam.name());
            append(xml, "Expiration", basicParam.expiration());
            append(xml, "HeartBeatInterval", basicParam.heartbeatInterval());
            append(xml, "HeartBeatCount", basicParam.heartbeatCount());
            xml.append(INDENT).append("</BasicParam>").append(LF);
        }
        return end(xml, "Response");
    }

    /**
     * 预置位查询应答（A.2.6 k) / §9.5.3.5）。
     *
     * <p>{@code PresetList} 必须出现，即使为空也要带 {@code Num="0"}：上级据此区分
     * 「设备没有预置位」与「应答缺字段」。
     */
    public static String presetQueryResponse(String deviceId, String sn, List<PresetItemView> items) {
        int size = items == null ? 0 : items.size();
        StringBuilder xml = start("Response");
        append(xml, "CmdType", "PresetQuery");
        append(xml, "SN", sn);
        append(xml, "DeviceID", deviceId);
        xml.append(INDENT).append("<PresetList Num=\"").append(size).append("\">").append(LF);
        if (size > 0) {
            for (PresetItemView item : items) {
                xml.append(INDENT).append("<Item>").append(LF);
                append(xml, "PresetID", item.presetId());
                append(xml, "PresetName", item.presetName());
                xml.append(INDENT).append("</Item>").append(LF);
            }
        }
        xml.append(INDENT).append("</PresetList>").append(LF);
        return end(xml, "Response");
    }

    /** 报文骨架：声明 + 根元素开标签。 */
    public static StringBuilder start(String root) {
        return new StringBuilder(256)
            .append(DECLARATION).append(LF)
            .append('<').append(root).append('>').append(LF);
    }

    /** 报文收尾：根元素闭标签 + 结尾换行（与目录应答同形）。 */
    public static String end(StringBuilder xml, String root) {
        return xml.append("</").append(root).append('>').append(LF).toString();
    }

    /**
     * 写一行元素：值为 {@code null} 或空白时整段省略。
     *
     * <p>业务码（如 {@code PresetID=0}）需要保留时用 {@code String.valueOf} 显式传值，不要依赖省略规则。
     */
    public static void append(StringBuilder xml, String name, Object value) {
        String text = value == null ? null : String.valueOf(value);
        if (StringUtils.isBlank(text)) {
            return;
        }
        xml.append(INDENT).append('<').append(name).append('>')
            .append(escapeXml(text))
            .append("</").append(name).append('>').append(LF);
    }

    /** 名称/地址可能含 {@code & < > " '}，不转义会让整条报文变成非法 XML 被上级丢弃。 */
    public static String escapeXml(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 设备信息应答字段（{@code Channel} 单独渲染，故不放进空值省略逻辑）。 */
    public record DeviceInfoView(String deviceId, String sn, String deviceName, String result,
                                 String manufacturer, String model, String firmware, int channel) {
    }

    /** 设备状态应答字段。 */
    public record DeviceStatusView(String deviceId, String sn, String result, boolean online,
                                   String encode, String record, LocalDateTime deviceTime) {
    }

    /** 基本参数（§9.5.3.4）：注册有效期 / 心跳间隔 / 心跳超时次数。 */
    public record BasicParamView(String name, Integer expiration, Integer heartbeatInterval,
                                 Integer heartbeatCount) {
    }

    /** 预置位条目（§9.5.3.5）。 */
    public record PresetItemView(String presetId, String presetName) {
    }
}
