package io.github.lunasaw.voglander.web.api.live.domain;

import io.github.lunasaw.zlm.entity.PlayUrl;
import lombok.Data;

@Data
public class LivePlayVO {
    private String  streamId;
    private String  callId;
    private Integer status;
    private PlayUrl playUrls;
    private long    refCount;

    /** 视频编码（H264 / H265 / UNKNOWN），前端据此选播放器 */
    private String  videoCodec;
    /** 视频宽，未知为 0 */
    private Integer videoWidth;
    /** 视频高，未知为 0 */
    private Integer videoHeight;
}
