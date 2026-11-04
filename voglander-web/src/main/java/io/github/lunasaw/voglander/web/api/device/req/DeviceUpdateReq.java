package io.github.lunasaw.voglander.web.api.device.req;

import io.github.lunasaw.voglander.common.enums.DeviceAgreementEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 设备更新请求对象
 *
 * @author luna
 * @date 2024/01/30
 */
@Data
public class DeviceUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（更新必需）
     */
    private Long              id;

    /**
     * 设备ID
     */
    private String            deviceId;

    /**
     * 自定义名称
     */
    private String            name;

    /**
     * IP地址
     */
    private String            ip;

    /**
     * 端口
     */
    private Integer           port;

    /**
     * 协议类型 {@link DeviceAgreementEnum}
     */
    private Integer           type;

    /**
     * 设备种类 {@link io.github.lunasaw.voglander.common.enums.DeviceSubTypeEnum}
     */
    private Integer           subType;

    /**
     * 设备协议 {@link io.github.lunasaw.voglander.common.enums.DeviceProtocolEnum}
     */
    private Integer           protocol;

    /**
     * 注册节点IP
     */
    private String            serverIp;

    /**
     * 状态 1在线 0离线
     */
    private Integer           status;

    /**
     * 所属级联组织 {@code tb_cascade_org.id}。
     *
     * <p>三态：{@code null} 不改 / {@code 0} 清空（上报时挂域节点）/ 其余按 id 归组织。
     */
    private Long              orgId;

    /**
     * 本地启用 1/0，{@code null} 不改。与在线状态 {@code status} 无关：
     * {@code status=0} 只是掉线（目录里仍出现），{@code enabled=0} 才从所有平台消失。
     */
    private Integer           enabled;

    /**
     * 扩展信息
     */
    private ExtendInfoReq     extendInfo;

    @Data
    public static class ExtendInfoReq {
        /**
         * 设备序列号
         */
        private String  serialNumber;

        /**
         * 传输协议 UDP/TCP
         */
        private String  transport;

        /**
         * 注册有效期
         */
        private Integer expires;

        /**
         * 密码
         */
        private String  password;

        /**
         * 数据流传输模式
         */
        private String  streamMode;

        /**
         * 编码
         */
        private String  charset;

        /**
         * RTSP 拉流地址（RTSP/ONVIF 协议设备专用）
         */
        private String  rtspUrl;

        /**
         * RTSP 辅码流（子码流）拉流地址（RTSP/ONVIF 协议设备专用，可选）
         */
        private String  rtspSubUrl;

        /**
         * 设备信息
         */
        private String  deviceInfo;
    }
}