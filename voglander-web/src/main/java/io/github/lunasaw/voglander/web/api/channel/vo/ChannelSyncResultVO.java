package io.github.lunasaw.voglander.web.api.channel.vo;

import java.util.List;

import lombok.Data;

/**
 * 通道同步结果 VO。
 *
 * <p>
 * {@link #persisted} 如实反映"有没有落库"：为 {@code false} 时前端应提示"同步失败，已保留原有通道"，
 * 而不是"同步成功 0 个通道"——后者会让人误以为设备真的没有通道而手工删数据。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Data
public class ChannelSyncResultVO {

    /**
     * 设备 ID
     */
    private String                  deviceId;

    /**
     * 厂商标识（hikvision / …）
     */
    private String                  vendor;

    /**
     * 设备型号
     */
    private String                  deviceModel;

    /**
     * 设备固件版本
     */
    private String                  firmwareVersion;

    /**
     * 是否已落库
     */
    private boolean                 persisted;

    /**
     * 发现到的通道数
     */
    private int                     discovered;

    /**
     * 落库影响行数
     */
    private int                     affected;

    /**
     * 在线通道数
     */
    private int                     onlineCount;

    /**
     * 失败原因；成功时为 null
     */
    private String                  failureReason;

    /**
     * 通道列表（落库成功时来自库值）
     */
    private List<DeviceChannelVO>   channels;
}
