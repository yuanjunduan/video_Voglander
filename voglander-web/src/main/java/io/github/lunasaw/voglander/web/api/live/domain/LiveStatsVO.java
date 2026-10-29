package io.github.lunasaw.voglander.web.api.live.domain;

import lombok.Data;

/**
 * 直播流实时指标 VO（媒体调试面板数据源）。
 * <p>
 * 只包含媒体服务器可客观提供的字段；网络时延与丢包率需 RTCP 统计，
 * 本链路不具备，故不下发——前端显式展示为「不可测」而不是拿估算值充数。
 * </p>
 *
 * @author luna
 */
@Data
public class LiveStatsVO {

    /** 流标识 */
    private String  streamId;

    /** 流是否存活 */
    private boolean online;

    /** 视频编码（H264 / H265 / …） */
    private String  codec;

    /** 视频宽 */
    private Integer width;

    /** 视频高 */
    private Integer height;

    /** 视频帧率 */
    private Integer fps;

    /** 数据产生速率，单位 byte/s */
    private Long    bytesSpeed;

    /** 本协议观看人数 */
    private Integer readerCount;

    /** 观看总人数 */
    private Integer totalReaderCount;

    /** 流存活时长，单位秒 */
    private Integer aliveSecond;

    /** 产生源地址 */
    private String  originUrl;
}
