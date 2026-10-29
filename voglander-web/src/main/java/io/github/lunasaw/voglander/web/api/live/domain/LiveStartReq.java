package io.github.lunasaw.voglander.web.api.live.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LiveStartReq {
    @NotBlank private String deviceId;
    /**
     * 通道 ID。GB28181 必填（按通道寻址 INVITE）；RTSP/ONVIF 拉流可不传（按设备级 rtspUrl 拉流）。
     */
    private String channelId;
    private String protocol   = "FLV";
    private String streamMode = "UDP";
    /**
     * 码流档次：main=主码流（默认）/ sub=辅码流。
     * <p>仅 RTSP/ONVIF 拉流设备支持；GB28181 收到 sub 会明确失败，不做静默降级。</p>
     */
    private String  streamProfile   = "main";
    /**
     * 切换码流时是否先立即回收另一档码流（切换即替换）。
     * <p>仅当另一档无人观看（refCount ≤ 1）时回收；有他人在看则整单失败，不踢人。</p>
     */
    private boolean replacePrevious = false;
}
