package io.github.lunasaw.voglander.web.api.cascade.vo;

import java.io.Serializable;
import java.util.List;

import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRuntimeSnapshotDTO;
import lombok.Data;

/**
 * 级联运行状态快照 VO（Web 出参，时间字段统一 Unix 毫秒）。
 *
 * <p>只增不改：DTO 的内部结构变动不应直接把页面打穿，因此这里逐字段映射一遍。
 *
 * @author luna
 */
@Data
public class CascadeRuntimeSnapshotVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 快照生成时间（unix 毫秒） */
    private Long                      generatedAt;

    /** 进行中的目录分片批次 */
    private List<CatalogBatchVO>      catalogBatches;

    /** 已发出、等 200 OK 的分片 */
    private List<InflightPageVO>      inflightPages;

    /** 已应答 INVITE、尚未推流的媒体会话 */
    private List<MediaSessionVO>      pendingSessions;

    /** 推流中的媒体会话 */
    private List<MediaSessionVO>      activeSessions;

    /** 等待上级确认的主动推送 */
    private List<PendingNotifyVO>     pendingNotifies;

    /** 生效中的上级订阅 */
    private List<CascadeSubscribeVO>  activeSubscriptions;

    /**
     * DTO → VO 转换。
     *
     * @param dto 运行状态快照
     * @return VO；入参为空返回 null
     */
    public static CascadeRuntimeSnapshotVO convertVO(CascadeRuntimeSnapshotDTO dto) {
        if (dto == null) {
            return null;
        }
        CascadeRuntimeSnapshotVO vo = new CascadeRuntimeSnapshotVO();
        vo.setGeneratedAt(dto.generatedAt());
        vo.setCatalogBatches(mapList(dto.catalogBatches(), CatalogBatchVO::convertVO));
        vo.setInflightPages(mapList(dto.inflightPages(), InflightPageVO::convertVO));
        vo.setPendingSessions(mapList(dto.pendingSessions(), MediaSessionVO::convertVO));
        vo.setActiveSessions(mapList(dto.activeSessions(), MediaSessionVO::convertVO));
        vo.setPendingNotifies(mapList(dto.pendingNotifies(), PendingNotifyVO::convertVO));
        vo.setActiveSubscriptions(mapList(dto.activeSubscriptions(), CascadeSubscribeVO::convertVO));
        return vo;
    }

    private static <S, T> List<T> mapList(List<S> source, java.util.function.Function<S, T> mapper) {
        return source == null ? List.of() : source.stream().map(mapper).toList();
    }

    /** 进行中的一批目录分片。 */
    @Data
    public static class CatalogBatchVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 批次键 {@code platformId#sn} */
        private String            key;
        /** 上级平台编码 */
        private String            platformId;
        /** 上级查询/订阅的 SN */
        private String            sn;
        /** 总节点数 */
        private Integer           sumNum;
        /** 已发页数 */
        private Integer           sentPages;
        /** 总页数 */
        private Integer           totalPages;
        /** 批次创建时间（unix 毫秒） */
        private Long              startAt;
        /** 当前在途分片已等待毫秒数 */
        private Long              waitingMs;

        static CatalogBatchVO convertVO(CascadeRuntimeSnapshotDTO.CatalogBatch dto) {
            CatalogBatchVO vo = new CatalogBatchVO();
            vo.setKey(dto.key());
            vo.setPlatformId(dto.platformId());
            vo.setSn(dto.sn());
            vo.setSumNum(dto.sumNum());
            vo.setSentPages(dto.sentPages());
            vo.setTotalPages(dto.totalPages());
            vo.setStartAt(dto.startAt());
            vo.setWaitingMs(dto.waitingMs());
            return vo;
        }
    }

    /** 已发出、等 200 OK 的分片。 */
    @Data
    public static class InflightPageVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 分片的 Call-ID */
        private String            callId;
        /** 所属批次键 */
        private String            batchKey;
        /** 已等待毫秒数 */
        private Long              waitingMs;

        static InflightPageVO convertVO(CascadeRuntimeSnapshotDTO.InflightPage dto) {
            InflightPageVO vo = new InflightPageVO();
            vo.setCallId(dto.callId());
            vo.setBatchKey(dto.batchKey());
            vo.setWaitingMs(dto.waitingMs());
            return vo;
        }
    }

    /** 媒体会话（实时/回放/下载共用）。 */
    @Data
    public static class MediaSessionVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** INVITE 的 Call-ID */
        private String            callId;
        /** PENDING=已应答未推流，ACTIVE=推流中 */
        private String            phase;
        /** 上级指定的 SSRC */
        private String            ssrc;
        /** 真实设备编码 */
        private String            localDeviceId;
        /** 上级寻址用的级联通道编码 */
        private String            upperUserId;
        /** 是否回放 */
        private Boolean           playback;
        /** 是否录像下载 */
        private Boolean           download;
        /** ZLM 应用名 */
        private String            app;
        /** ZLM 流 ID */
        private String            stream;
        /** 上级收流地址 */
        private String            dstUrl;
        /** 上级收流端口 */
        private Integer           dstPort;
        /** 是否 UDP 收流 */
        private Boolean           udp;
        /** 本端 RTP 发送端口 */
        private Integer           srcPort;
        /** 会话创建时间（unix 毫秒） */
        private Long              createdAt;
        /** 最近活动时间（unix 毫秒） */
        private Long              lastActiveAt;

        static MediaSessionVO convertVO(CascadeRuntimeSnapshotDTO.MediaSessionInfo dto) {
            MediaSessionVO vo = new MediaSessionVO();
            vo.setCallId(dto.callId());
            vo.setPhase(dto.phase());
            vo.setSsrc(dto.ssrc());
            vo.setLocalDeviceId(dto.localDeviceId());
            vo.setUpperUserId(dto.upperUserId());
            vo.setPlayback(dto.playback());
            vo.setDownload(dto.download());
            vo.setApp(dto.app());
            vo.setStream(dto.stream());
            vo.setDstUrl(dto.dstUrl());
            vo.setDstPort(dto.dstPort());
            vo.setUdp(dto.udp());
            vo.setSrcPort(dto.srcPort());
            vo.setCreatedAt(dto.createdAt());
            vo.setLastActiveAt(dto.lastActiveAt());
            return vo;
        }
    }

    /** 等待上级确认的主动推送。 */
    @Data
    public static class PendingNotifyVO implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 推送的 Call-ID */
        private String            callId;
        /** 目标上级平台编码 */
        private String            platformId;
        /** 推送内容描述 */
        private String            desc;
        /** 发出时间（unix 毫秒） */
        private Long              sentAt;
        /** 是否已重发过一次 */
        private Boolean           retried;

        static PendingNotifyVO convertVO(CascadeRuntimeSnapshotDTO.PendingNotify dto) {
            PendingNotifyVO vo = new PendingNotifyVO();
            vo.setCallId(dto.callId());
            vo.setPlatformId(dto.platformId());
            vo.setDesc(dto.desc());
            vo.setSentAt(dto.sentAt());
            vo.setRetried(dto.retried());
            return vo;
        }
    }
}
