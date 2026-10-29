package io.github.lunasaw.voglander.web.api.device.req;

import io.github.lunasaw.voglander.common.enums.DeviceAgreementEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 设备创建请求对象
 * @author luna
 * @date 2024/01/30
 */
@Data
public class DeviceCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备ID
     */
    private String deviceId;

    /**
     * 自定义名称
     */
    private String name;

    /**
     * IP地址
     */
    private String ip;

    /**
     * 端口
     */
    private Integer port;

    /**
     * 协议类型 {@link DeviceAgreementEnum}
     */
    private Integer type;

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
    private String serverIp;

    /**
     * 初始在线态：仅拉流设备（ONVIF/RTSP）创建时使用。
     *
     * <p>拉流设备没有 SIP 注册与心跳信令，{@code patchLiveness} 不会被触发，故允许把创建前的
     * RTSP 探活结果作为初始值上报；仅识别 1，其余取值（含缺省）一律按离线处理。</p>
     */
    private Integer status;

    /**
     * 扩展信息
     */
    private ExtendInfoReq extendInfo;

    @Data
    public static class ExtendInfoReq {
        /**
         * 设备序列号
         */
        private String serialNumber;

        /**
         * 传输协议 UDP/TCP
         */
        private String transport;

        /**
         * 注册有效期
         */
        private Integer expires;

        /**
         * 密码
         */
        private String password;

        /**
         * 数据流传输模式
         */
        private String streamMode;

        /**
         * 编码
         */
        private String charset;

        /**
         * RTSP 拉流地址（RTSP/ONVIF 协议设备专用），形如 rtsp://user:pwd@1.2.3.4:554/Streaming/Channels/101
         */
        private String rtspUrl;

        /**
         * RTSP 辅码流（子码流）拉流地址（RTSP/ONVIF 协议设备专用，可选），形如 rtsp://user:pwd@1.2.3.4:554/Streaming/Channels/102
         */
        private String rtspSubUrl;

        /**
         * 设备信息
         */
        private String deviceInfo;
    }
}