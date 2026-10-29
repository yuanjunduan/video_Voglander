package io.github.lunasaw.voglander.service.device.dto;

import java.util.List;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import lombok.Data;

/**
 * 一次通道同步的结果（返回给 Web 层转 VO）。
 *
 * <p>
 * {@link #persisted} 必须如实反映"有没有落库"：发现失败时台账保持原样，
 * 前端要据此决定是提示"同步失败，已保留原有通道"而不是"同步成功 0 个通道"。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Data
public class ChannelSyncResultDTO {

    /**
     * 设备 ID
     */
    private String                  deviceId;

    /**
     * 厂商标识（hikvision / …）；失败且未识别出厂商时为 null
     */
    private String                  vendor;

    /**
     * 设备型号（设备自己报的）
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
     * 落库影响行数（新增 + 更新）
     */
    private int                     affected;

    /**
     * 发现到的通道中在线数量
     */
    private int                     onlineCount;

    /**
     * 失败原因；成功时为 null
     */
    private String                  failureReason;

    /**
     * 预览的通道列表（未落库时来自设备应答，落库后来自落库数据）
     */
    private List<DeviceChannelDTO>  channels;
}
