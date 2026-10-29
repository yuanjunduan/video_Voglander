package io.github.lunasaw.voglander.service.live.dto;

import io.github.lunasaw.zlm.entity.PlayUrl;
import lombok.Data;

/**
 * 直播/回放播放信息 DTO（编排核心返回值）。
 *
 * @author luna
 */
@Data
public class LivePlayDTO {

    /**
     * 前端稳定主键。
     */
    private String  streamId;

    /**
     * SIP Call-ID（INVITE 成功后回填，可能为空）。
     */
    private String  callId;

    /**
     * 会话状态，取值见 {@code MediaSessionConstant.Status}。
     */
    private Integer status;

    /**
     * ZLM 多协议播放地址。
     */
    private PlayUrl playUrls;

    /**
     * 观看者引用计数。
     */
    private long    refCount;

    /**
     * 视频编码（H264 / H265 / UNKNOWN）。前端据此决定用原生播放器还是 WASM 软解。
     */
    private String  videoCodec;

    /**
     * 视频宽，未知为 0。
     */
    private Integer videoWidth;

    /**
     * 视频高，未知为 0。
     */
    private Integer videoHeight;
}
