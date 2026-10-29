package io.github.lunasaw.voglander.service.live.codec;

/**
 * 流视频轨信息快照。
 * <p>
 * 宽度/高度用于前端估算软解开销与展示分辨率；取不到时为 0，不参与任何判定逻辑。
 * </p>
 *
 * @param codec  视频编码，非 null
 * @param width  视频宽，未知为 0
 * @param height 视频高，未知为 0
 * @author luna
 */
public record StreamVideoInfo(VideoCodec codec, int width, int height) {

    /** 未知快照，等价于"没查到视频轨" */
    public static final StreamVideoInfo UNKNOWN = new StreamVideoInfo(VideoCodec.UNKNOWN, 0, 0);

    public StreamVideoInfo {
        if (codec == null) {
            codec = VideoCodec.UNKNOWN;
        }
    }

    public static StreamVideoInfo of(VideoCodec codec, int width, int height) {
        return new StreamVideoInfo(codec, width, height);
    }
}
