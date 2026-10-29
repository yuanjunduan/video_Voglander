package io.github.lunasaw.voglander.intergration.wrapper.channel.dto;

import java.util.List;

import lombok.Data;

/**
 * 一次通道发现的结果。
 *
 * <p>
 * 成功/失败都用本对象返回，不抛业务异常：发现失败的关键信息是"为什么失败"，
 * 上层要据此决定是"报错给用户"还是"保持库里通道不动"。失败时 {@link #channels} 为空，
 * 调用方**必须**据此跳过落库，避免把一次网络抖动当成"设备现在没有通道"而清理掉台账。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Data
public class DiscoverResult {

    /**
     * 是否成功拿到通道清单
     */
    private boolean              success;

    /**
     * 厂商标识（hikvision / dahua / onvif）
     */
    private String               vendor;

    /**
     * 失败原因（失败时必有值，可直接透给前端展示）
     */
    private String               failureReason;

    /**
     * 设备型号（发现过程中从设备自身取到，用于确认"这确实是那台设备"）
     */
    private String               deviceModel;

    /**
     * 设备固件版本
     */
    private String               firmwareVersion;

    /**
     * 发现的通道；成功但设备确实没有通道时为空列表
     */
    private List<DiscoveredChannel> channels;

    public static DiscoverResult success(String vendor, List<DiscoveredChannel> channels) {
        DiscoverResult result = new DiscoverResult();
        result.setSuccess(true);
        result.setVendor(vendor);
        result.setChannels(channels == null ? List.of() : channels);
        return result;
    }

    public static DiscoverResult failure(String vendor, String failureReason) {
        DiscoverResult result = new DiscoverResult();
        result.setSuccess(false);
        result.setVendor(vendor);
        result.setFailureReason(failureReason);
        result.setChannels(List.of());
        return result;
    }
}
