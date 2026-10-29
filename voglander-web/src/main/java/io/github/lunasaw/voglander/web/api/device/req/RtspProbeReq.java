package io.github.lunasaw.voglander.web.api.device.req;

import java.io.Serializable;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RTSP 地址连通性探测请求。
 *
 * <p>
 * 只探"地址能不能通"，不落库、不起流，因此不携带 deviceId——
 * 新建设备时记录还不存在，编辑时前端手上的 URL 也可能刚改过。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
@Data
public class RtspProbeReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待探测的拉流地址，形如 rtsp://user:pwd@192.168.1.64:554/Streaming/Channels/101
     */
    @NotBlank(message = "RTSP 地址不能为空")
    private String rtspUrl;
}
