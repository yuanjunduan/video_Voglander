package io.github.lunasaw.voglander.web.api.channel.req;

import lombok.Data;

import java.io.Serializable;

/**
 * 设备通道创建请求对象
 * @author luna
 * @date 2024/01/30
 */
@Data
public class DeviceChannelCreateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 通道Id
     */
    private String channelId;

    /**
     * 设备ID
     */
    private String deviceId;

    /**
     * 通道名称
     */
    private String name;

    /**
     * 所属级联组织 {@code tb_cascade_org.id}，null 或 0 表示跟随设备归属。
     */
    private Long   orgId;

    /**
     * 本地启用 1/0，null 走库默认（启用）。与在线状态无关。
     */
    private Integer enabled;

    /**
     * 扩展信息
     */
    private ExtendInfoReq extendInfo;

    @Data
    public static class ExtendInfoReq {
        /**
         * 设备通道信息
         */
        private String channelInfo;
    }
}