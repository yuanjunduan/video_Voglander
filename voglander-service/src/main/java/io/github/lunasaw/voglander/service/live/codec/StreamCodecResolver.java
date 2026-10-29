package io.github.lunasaw.voglander.service.live.codec;

import io.github.lunasaw.zlm.config.ZlmNode;

/**
 * 流视频轨解析器。
 * <p>
 * 抽成接口而非在编排层直接调 {@code ZlmRestService} 静态方法，是为了让
 * {@code MediaPlayServiceImpl} 的用例能注入替身，避免 mock 静态方法。
 * </p>
 *
 * @author luna
 */
public interface StreamCodecResolver {

    /**
     * 解析指定流的视频轨信息。
     * <p>
     * 实现必须吞掉所有异常：编码解析只用于前端选播放器，失败不能影响起流主流程。
     * </p>
     *
     * @param node   ZLM 节点（host + secret），可为 null
     * @param app    应用名
     * @param stream 流 ID
     * @return 视频轨信息；查不到 / 未就绪 / 异常时返回 {@link StreamVideoInfo#UNKNOWN}
     */
    StreamVideoInfo resolve(ZlmNode node, String app, String stream);
}
