package io.github.lunasaw.voglander.service.live.dto;

import lombok.Data;

/**
 * 直播流实时运行指标（ZLM {@code getMediaList} 视角）。
 * <p>
 * 只承载媒体服务器能客观给出的数据。网络时延与丢包率依赖 RTCP 统计，浏览器端与 ZLM 均不提供，
 * 因此不在此 DTO 内——前端对这两项显式标注「不可测」，不用估算值填充。
 * </p>
 *
 * @author luna
 */
@Data
public class LiveStreamStatsDTO {

    /** 流标识（与 live/start 返回的 streamId 一致） */
    private String  streamId;

    /** 流是否在 ZLM 上存活；false 时其余字段为默认值 */
    private boolean online;

    /** 视频编码名（H264 / H265 / …），取自视频轨 */
    private String  codec;

    /** 视频宽，未知为 0 */
    private Integer width;

    /** 视频高，未知为 0 */
    private Integer height;

    /** 视频帧率，未知为 0 */
    private Integer fps;

    /** 数据产生速率，单位 byte/s（前端换算 kbps） */
    private Long    bytesSpeed;

    /** 本协议观看人数 */
    private Integer readerCount;

    /** 观看总人数（含 hls/rtsp/rtmp/http-flv/ws-flv） */
    private Integer totalReaderCount;

    /** 流存活时长，单位秒 */
    private Integer aliveSecond;

    /** 产生源的 url（拉流设备即实际拉的 RTSP 地址，排障关键） */
    private String  originUrl;
}
