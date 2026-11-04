package io.github.lunasaw.voglander.web.api.channel.req;

import lombok.Data;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 设备通道更新请求对象
 * @author luna
 * @date 2024/01/30
 */
@Data
public class DeviceChannelUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（更新必需）
     */
    @NotNull(message = "设备通道ID不能为空")
    private Long id;

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
     * 状态 1在线 0离线
     */
    private Integer status;

    /**
     * 所属级联组织 {@code tb_cascade_org.id}。
     *
     * <p>三态：{@code null} 不改 / {@code 0} 清空（改为跟随设备归属）/ 其余按 id 归组织。
     */
    private Long    orgId;

    /**
     * 本地启用 1/0，{@code null} 不改。与在线状态无关：停用后从所有上级平台目录消失。
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