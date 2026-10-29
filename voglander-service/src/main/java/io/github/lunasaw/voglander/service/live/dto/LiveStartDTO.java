package io.github.lunasaw.voglander.service.live.dto;

import lombok.Data;

/**
 * 开始直播请求 DTO。
 *
 * @author luna
 */
@Data
public class LiveStartDTO {

    /**
     * 设备 GB28181 编码
     */
    private String deviceId;

    /**
     * 通道 GB28181 编码
     */
    private String channelId;

    /**
     * 播放协议偏好（FLV/HLS/...），前端自适应，仅作记录。
     */
    private String protocol   = "FLV";

    /**
     * 拉流模式 UDP / TCP_ACTIVE / TCP_PASSIVE。
     */
    private String streamMode = "UDP";

    /**
     * 码流档次：main=主码流（默认）/ sub=辅码流，见 {@link io.github.lunasaw.voglander.common.enums.StreamProfileEnum}。
     */
    private String streamProfile = "main";

    /**
     * 切换码流时是否先立即回收另一档码流（切换即替换）。
     * <p>仅当另一档无人观看时回收；有他人在看则拒绝，避免踢掉别人的画面。</p>
     */
    private boolean replacePrevious = false;
}
