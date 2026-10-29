package io.github.lunasaw.voglander.intergration.wrapper.channel.dto;

import lombok.Data;

/**
 * 通道发现结果中的单条通道（厂商无关）。
 *
 * <p>
 * 只描述"设备告诉我们它有什么通道"，不含任何落库逻辑。地址由各厂商发现器按自家规则拼装，
 * 凭据来自设备级已配置的拉流地址，不在通道上另存一份。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Data
public class DiscoveredChannel {

    /**
     * 通道 ID：同一设备内唯一。海康取 NVR 通道号（"1"…"8"），与 RTSP 路径里的 {@code <通道号>01} 对应
     */
    private String  channelId;

    /**
     * 通道名称（设备侧配置的名字）
     */
    private String  name;

    /**
     * 在线状态：1 在线 / 0 离线；设备未上报时为 null（保持库里原值不覆盖）
     */
    private Integer status;

    /**
     * 主码流地址（含凭据）
     */
    private String  rtspUrl;

    /**
     * 辅码流地址（含凭据）；设备不支持第二路编码时为 null
     */
    private String  rtspSubUrl;

    /**
     * NVR 侧接入该通道所用的协议（如海康 ISAPI 的 proxyProtocol：HIKVISION / ONVIF）
     */
    private String  proxyProtocol;

    /**
     * 源设备 IP（NVR 代理接入的那台 IPC）
     */
    private String  sourceIp;

    /**
     * 源设备管理端口
     */
    private Integer sourcePort;

    /**
     * 源设备上的通道号
     */
    private Integer sourceChannel;

    /**
     * 源设备型号
     */
    private String  sourceModel;

    /**
     * 源设备固件版本
     */
    private String  sourceFirmware;
}
