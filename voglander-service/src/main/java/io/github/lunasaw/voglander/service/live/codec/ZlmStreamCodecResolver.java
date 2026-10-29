package io.github.lunasaw.voglander.service.live.codec;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.lunasaw.zlm.api.ZlmRestService;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.entity.MediaData;
import io.github.lunasaw.zlm.entity.ServerResponse;
import io.github.lunasaw.zlm.entity.Track;
import io.github.lunasaw.zlm.entity.req.MediaReq;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 ZLM {@code getMediaList} 的视频轨解析实现。
 * <p>
 * 两个易错点（均已在真机核实）：
 * <ol>
 *   <li>同一条流按 schema（ts/rtsp/fmp4/hls/rtmp…）各返回一条 {@link MediaData}，编码相同，
 *       必须按 app + stream 精确匹配后取第一条有效结果，不能按返回顺序盲取；</li>
 *   <li>{@code codec_type} 为 0 才是视频轨（1 = 音频），不筛会把音频编码当成视频编码。</li>
 * </ol>
 * </p>
 *
 * @author luna
 */
@Slf4j
@Component
public class ZlmStreamCodecResolver implements StreamCodecResolver {

    /** ZLM 轨道类型：0 = 视频 */
    private static final int TRACK_TYPE_VIDEO = 0;

    @Override
    public StreamVideoInfo resolve(ZlmNode node, String app, String stream) {
        if (node == null || app == null || stream == null) {
            return StreamVideoInfo.UNKNOWN;
        }
        try {
            MediaReq req = new MediaReq();
            req.setApp(app);
            req.setStream(stream);
            ServerResponse<List<MediaData>> resp =
                ZlmRestService.getMediaList(node.getHost(), node.getSecret(), req);
            if (resp == null || resp.getCode() == null || resp.getCode() != 0) {
                log.debug("getMediaList 返回非成功, app={}, stream={}, resp={}", app, stream, resp);
                return StreamVideoInfo.UNKNOWN;
            }
            return pick(app, stream, resp.getData());
        } catch (Exception e) {
            // 编码解析属旁路信息，任何异常都不允许影响起流
            log.warn("解析流视频轨异常, app={}, stream={}: {}", app, stream, e.getMessage());
            return StreamVideoInfo.UNKNOWN;
        }
    }

    /**
     * 从 mediaList 中挑出目标流的视频轨信息（包内可见，便于单测覆盖多 schema 去重与轨道过滤）。
     *
     * @param app       目标应用名
     * @param stream    目标流 ID
     * @param mediaList ZLM 返回的媒体列表，可为 null
     * @return 首个视频轨信息；无匹配或无有效视频轨时为 {@link StreamVideoInfo#UNKNOWN}
     */
    static StreamVideoInfo pick(String app, String stream, List<MediaData> mediaList) {
        if (mediaList == null) {
            return StreamVideoInfo.UNKNOWN;
        }
        for (MediaData media : mediaList) {
            if (media == null || !app.equals(media.getApp()) || !stream.equals(media.getStream())) {
                continue;
            }
            StreamVideoInfo info = firstVideoTrack(media.getTracks());
            if (!info.codec().isUnknown()) {
                return info;
            }
        }
        return StreamVideoInfo.UNKNOWN;
    }

    private static StreamVideoInfo firstVideoTrack(Track[] tracks) {
        if (tracks == null) {
            return StreamVideoInfo.UNKNOWN;
        }
        for (Track track : tracks) {
            if (track == null || track.getCodecType() != TRACK_TYPE_VIDEO) {
                continue;
            }
            VideoCodec codec = VideoCodec.fromCodecIdName(track.getCodecIdName());
            if (!codec.isUnknown()) {
                return StreamVideoInfo.of(codec, track.getWidth(), track.getHeight());
            }
        }
        return StreamVideoInfo.UNKNOWN;
    }
}
