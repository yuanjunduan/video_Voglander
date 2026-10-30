package io.github.lunasaw.voglander.manager.domaon.dto.cascade;

import java.util.List;

/**
 * 级联运行状态快照：把散落在各协议组件内存里的在途状态汇总成一份可读数据。
 *
 * <p>这些状态原先只存在于 {@code ConcurrentHashMap} 与日志里——目录分片卡在第几页、
 * 上级点播的会话停在「已应答未推流」还是「推流中」、哪条推送在等确认，都只能翻日志猜。
 * 快照只是<b>只读投影</b>，不参与任何协议决策，也不持有锁以外的共享可变状态。
 *
 * <p>所有时间字段都是 Unix 毫秒（Web 出参规约），{@code waitingMs}/{@code ageMs}
 * 由服务端算好，避免前端与服务器时钟不一致时显示成负数。
 *
 * @param generatedAt         快照生成时间
 * @param catalogBatches      进行中的目录分片批次
 * @param inflightPages       已发出、等 200 OK 的分片
 * @param pendingSessions     已应答 INVITE、尚未推流的媒体会话
 * @param activeSessions      推流中的媒体会话
 * @param pendingNotifies     等待上级确认的主动推送
 * @param activeSubscriptions 生效中的上级订阅
 * @author luna
 */
public record CascadeRuntimeSnapshotDTO(long generatedAt,
    List<CatalogBatch> catalogBatches,
    List<InflightPage> inflightPages,
    List<MediaSessionInfo> pendingSessions,
    List<MediaSessionInfo> activeSessions,
    List<PendingNotify> pendingNotifies,
    List<CascadeSubscribeDTO> activeSubscriptions) {

    /**
     * 进行中的一批目录分片。
     *
     * @param key       批次键 {@code platformId#sn}
     * @param platformId 上级平台编码
     * @param sn         上级查询/订阅的 SN
     * @param sumNum     总节点数（每包 SumNum 恒为本值）
     * @param sentPages  已发页数
     * @param totalPages 总页数
     * @param startAt    批次创建时间
     * @param waitingMs  当前在途分片已等待的毫秒数
     */
    public record CatalogBatch(String key, String platformId, String sn, int sumNum, int sentPages, int totalPages,
        long startAt, long waitingMs) {
    }

    /**
     * 已发出、等待 200 OK 的分片。
     *
     * @param callId  分片的 Call-ID
     * @param batchKey 所属批次键
     * @param waitingMs 已等待毫秒数（超过超时阈值即被看门狗推进）
     */
    public record InflightPage(String callId, String batchKey, long waitingMs) {
    }

    /**
     * 媒体会话。
     *
     * @param callId        INVITE 的 Call-ID
     * @param phase         {@code PENDING}=已应答未推流，{@code ACTIVE}=推流中
     * @param ssrc          上级指定的 SSRC
     * @param localDeviceId 真实设备编码
     * @param upperUserId   上级寻址用的级联通道编码
     * @param playback      是否回放会话
     * @param download      是否录像下载会话
     * @param app           ZLM 应用名
     * @param stream        ZLM 流 ID
     * @param dstUrl        上级收流地址
     * @param dstPort       上级收流端口
     * @param udp           是否 UDP 收流
     * @param srcPort       本端 RTP 发送端口
     * @param createdAt     会话创建时间
     * @param lastActiveAt  最近活动时间
     */
    public record MediaSessionInfo(String callId, String phase, String ssrc, String localDeviceId,
        String upperUserId, boolean playback, boolean download, String app, String stream, String dstUrl, int dstPort,
        boolean udp, int srcPort, long createdAt, long lastActiveAt) {
    }

    /**
     * 等待上级确认的主动推送。
     *
     * @param callId     推送的 Call-ID
     * @param platformId 目标上级平台编码
     * @param desc       推送内容描述
     * @param sentAt     发出时间
     * @param retried    是否已重发过一次
     */
    public record PendingNotify(String callId, String platformId, String desc, long sentAt, boolean retried) {
    }
}
