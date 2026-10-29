package io.github.lunasaw.voglander.service.live.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.lunasaw.zlm.entity.MediaData;
import io.github.lunasaw.zlm.entity.Track;

/**
 * 视频轨解析单测：覆盖编码名兼容、多 schema 去重、轨道类型过滤。
 *
 * @author luna
 */
class ZlmStreamCodecResolverTest {

    private static final String APP    = "rtp";
    private static final String STREAM = "rtsp_live_dev001";

    // ---------- VideoCodec.fromCodecIdName ----------

    @Test
    @DisplayName("编码名 H264 应识别为 H264")
    void codec_h264Name_shouldBeH264() {
        assertEquals(VideoCodec.H264, VideoCodec.fromCodecIdName("H264"));
    }

    @Test
    @DisplayName("编码名 H265 / HEVC 均应识别为 H265")
    void codec_h265Aliases_shouldBeH265() {
        assertEquals(VideoCodec.H265, VideoCodec.fromCodecIdName("H265"));
        assertEquals(VideoCodec.H265, VideoCodec.fromCodecIdName("hevc"));
    }

    @Test
    @DisplayName("编码名 AVC 应识别为 H264")
    void codec_avcAlias_shouldBeH264() {
        assertEquals(VideoCodec.H264, VideoCodec.fromCodecIdName(" avc "));
    }

    @Test
    @DisplayName("空 / null / 无关编码名应为 UNKNOWN")
    void codec_blankOrUnknownName_shouldBeUnknown() {
        assertTrue(VideoCodec.fromCodecIdName(null).isUnknown());
        assertTrue(VideoCodec.fromCodecIdName("").isUnknown());
        assertTrue(VideoCodec.fromCodecIdName("   ").isUnknown());
        assertTrue(VideoCodec.fromCodecIdName("OPUS").isUnknown());
    }

    // ---------- ZlmStreamCodecResolver.pick ----------

    @Test
    @DisplayName("同流多 schema 重复返回时应取到视频轨编码与分辨率")
    void pick_multiSchemaDuplicates_shouldResolveVideoTrack() {
        List<MediaData> mediaList = new ArrayList<>();
        mediaList.add(media("rtp", STREAM, "ts", track(0, "H265", 2560, 1440)));
        mediaList.add(media("rtp", STREAM, "rtsp", track(0, "H265", 2560, 1440)));
        mediaList.add(media("rtp", STREAM, "fmp4", track(0, "H265", 2560, 1440)));

        StreamVideoInfo info = ZlmStreamCodecResolver.pick(APP, STREAM, mediaList);

        assertEquals(VideoCodec.H265, info.codec());
        assertEquals(2560, info.width());
        assertEquals(1440, info.height());
    }

    @Test
    @DisplayName("音频轨在前时应跳过，只取 codecType=0 的视频轨")
    void pick_audioTrackFirst_shouldSkipToVideoTrack() {
        Track audio = track(1, "AAC", 0, 0);
        Track video = track(0, "H264", 1920, 1080);
        List<MediaData> mediaList = List.of(media("rtp", STREAM, "ts", audio, video));

        StreamVideoInfo info = ZlmStreamCodecResolver.pick(APP, STREAM, mediaList);

        assertEquals(VideoCodec.H264, info.codec());
        assertEquals(1920, info.width());
    }

    @Test
    @DisplayName("非目标 app / stream 应被忽略")
    void pick_otherStreamOrApp_shouldBeIgnored() {
        List<MediaData> mediaList = List.of(
            media("live", "rtsp_src", "ts", track(0, "H264", 1280, 720)),
            media(APP, "other_stream", "ts", track(0, "H264", 1280, 720)));

        assertTrue(ZlmStreamCodecResolver.pick(APP, STREAM, mediaList).codec().isUnknown());
    }

    @Test
    @DisplayName("匹配到流但无视频轨时应为 UNKNOWN")
    void pick_noVideoTrack_shouldBeUnknown() {
        assertTrue(ZlmStreamCodecResolver.pick(APP, STREAM,
            List.of(media("rtp", STREAM, "ts", track(1, "AAC", 0, 0)))).codec().isUnknown());
        assertTrue(ZlmStreamCodecResolver.pick(APP, STREAM,
            List.of(media("rtp", STREAM, "ts"))).codec().isUnknown());
    }

    @Test
    @DisplayName("空列表 / null 列表应为 UNKNOWN 且不抛异常")
    void pick_emptyOrNullList_shouldBeUnknown() {
        assertTrue(ZlmStreamCodecResolver.pick(APP, STREAM, Collections.emptyList()).codec().isUnknown());
        assertTrue(ZlmStreamCodecResolver.pick(APP, STREAM, null).codec().isUnknown());
    }

    @Test
    @DisplayName("轨道编码名不可识别时应继续找下一条视频轨")
    void pick_unknownCodecThenKnown_shouldContinueScanning() {
        List<MediaData> mediaList = List.of(
            media("rtp", STREAM, "ts", track(0, "OPUS", 0, 0)),
            media("rtp", STREAM, "rtsp", track(0, "H264", 1280, 720)));

        StreamVideoInfo info = ZlmStreamCodecResolver.pick(APP, STREAM, mediaList);

        assertEquals(VideoCodec.H264, info.codec());
        assertEquals(1280, info.width());
    }

    // ---------- fixtures ----------

    private static MediaData media(String app, String stream, String schema, Track... tracks) {
        MediaData data = new MediaData();
        data.setApp(app);
        data.setStream(stream);
        data.setSchema(schema);
        data.setTracks(tracks);
        return data;
    }

    private static Track track(int codecType, String codecIdName, int width, int height) {
        Track track = new Track();
        track.setCodecType(codecType);
        track.setCodecIdName(codecIdName);
        track.setWidth(width);
        track.setHeight(height);
        return track;
    }
}
