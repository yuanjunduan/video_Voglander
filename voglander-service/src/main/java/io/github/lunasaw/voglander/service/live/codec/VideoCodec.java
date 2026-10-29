package io.github.lunasaw.voglander.service.live.codec;

import java.util.Locale;

/**
 * 流的视频编码类型。
 * <p>
 * 只承载"浏览器能否原生解码"这一层语义：H264 各浏览器原生支持；H265 依赖操作系统 HEVC 解码器
 * （Windows 需装 HEVC Video Extensions，多数 Linux 无），未安装时需由前端 WASM 软解。
 * </p>
 *
 * @author luna
 */
public enum VideoCodec {

    /** H.264 / AVC，浏览器原生可播 */
    H264,

    /** H.265 / HEVC，浏览器原生可播性取决于系统解码器 */
    H265,

    /**
     * 未知：流未就绪、无视频轨或查询失败。
     * <p>调用方按"先尝试原生播放、失败再降级"处理，不应据此判定不可播。</p>
     */
    UNKNOWN;

    /**
     * 由 ZLM {@code tracks[].codec_id_name} 解析编码。
     * <p>
     * ZLM 实测返回 {@code "H264"} / {@code "H265"}。此处同时兼容 {@code "AVC"} / {@code "HEVC"}
     * 写法，不同版本 ZLM 及第三方拉流源存在命名差异。
     * </p>
     *
     * @param codecIdName ZLM 轨道编码名，可为 null
     * @return 识别到的编码，无法识别时为 {@link #UNKNOWN}
     */
    public static VideoCodec fromCodecIdName(String codecIdName) {
        if (codecIdName == null || codecIdName.isBlank()) {
            return UNKNOWN;
        }
        String name = codecIdName.trim().toUpperCase(Locale.ROOT);
        if (name.contains("H264") || name.contains("AVC")) {
            return H264;
        }
        if (name.contains("H265") || name.contains("HEVC")) {
            return H265;
        }
        return UNKNOWN;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }
}
