package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.lunasaw.sip.common.transmit.event.Event;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.common.anno.TechnicalScheduler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 目录应答分片发送器（附录 N）。
 *
 * <p><b>为什么分片</b>：上级对单条 SIP MESSAGE 的长度有上限，整包下发时通道多的现场只会收到前几十个。
 * 首包只含域节点，让上级先建根，组织与通道才有父可挂；每包的 {@code SumNum} 都是<b>总节点数</b>
 * （框架的 {@code sendCatalogCommandBatched} 会把它改写成本包条数，上级据此以为已收满，所以不能用它）。
 *
 * <p><b>为什么要串行</b>：一次性 burst 掉所有分片在 UDP 下会被瞬时压满链路，表现为上级只收到前几包、
 * 后续节点缺失且重查也时好时坏。串行版做法是「上一页收到 200 OK 再发下一页」，上级来不及处理时不会再被灌包。
 *
 * <p><b>为什么不能死等 200 OK</b>：部分上级对本端分片的应答会丢弃或复用连接不复，死等会让目录下发永久卡在
 * 第一页。因此每页都配一个超时看门狗（{@code ok-timeout-ms}），到点无论有无应答都推进，
 * 两条路径通过 {@link #inflight} 的 {@code remove(callId)} 幂等去重。
 *
 * <p>Call-ID 约定：<b>首页沿用上级查询的 Call-ID</b>（上级据此把首包对上自己那条请求），
 * 其余分片各自新建，避免同一 Call-ID 承载多条无事务关联的报文被上级判重。
 *
 * @author luna
 */
@Slf4j
@Component
@TechnicalScheduler(category = TechnicalScheduler.Category.PROTOCOL)
@RequiredArgsConstructor
public class CascadeCatalogPager {

    private final CascadeUpperMessenger    cascadeUpperMessenger;
    private final CascadeCatalogAssembler  catalogAssembler;

    /**
     * 单条目录 MESSAGE 容纳的通道数上限。
     *
     * <p>上级（海康 iSecure Center 等）对单条 SIP MESSAGE 报文长度有限制，通道多时整包下发会被
     * 丢弃或截断，表现为"只有前几十个通道"。超过本值即分片下发。
     */
    @Value("${gateway.cascade.catalog.batch-size:50}")
    private int  catalogBatchSize = 50;

    /** 是否串行发送（等上一页 200 OK 再发下一页）；关掉则退回一次性 burst，便于现场快速对比。 */
    @Value("${gateway.cascade.catalog.serial:true}")
    private boolean serial = true;

    /** 单页等待 200 OK 的超时（毫秒）：超时即视为已送达并推进，避免上级不回 2xx 时目录卡死。 */
    @Value("${gateway.cascade.catalog.ok-timeout-ms:2000}")
    private long okTimeoutMs = 2000L;

    /** 看门狗扫描间隔（毫秒）。 */
    @Value("${gateway.cascade.catalog.scan-interval-ms:1000}")
    private long scanIntervalMs = 1000L;

    /** 整批目录的最大存活时间（毫秒）：超过即丢弃，防止上级中途断开时残留整棵目录树占用内存。 */
    @Value("${gateway.cascade.catalog.batch-ttl-ms:120000}")
    private long batchTtlMs = 120000L;

    /** 已发出、等 200 OK 的分片：Call-ID → 批次。 */
    private final ConcurrentHashMap<String, PageBatch> inflight = new ConcurrentHashMap<>();

    /** 进行中的批次：{@code platformId#sn} → 批次（同 SN 重查时替换旧批次）。 */
    private final ConcurrentHashMap<String, PageBatch> batches  = new ConcurrentHashMap<>();

    /**
     * 发送一次目录查询的完整应答。
     *
     * @param platform      上级平台配置（决定 From 身份，必须是 {@code local_client_id}）
     * @param inboundCallId 上级请求的 Call-ID，首页沿用；为空时新建
     * @param deviceId      上级请求的 DeviceID（原样带回）
     * @param sn            上级请求的 SN（原样带回）
     * @param nodes         本次目录的全部节点（首元素应为域节点）
     * @return 实际发出的包数；平台地址不可用返回 0
     */
    public int sendCatalog(CascadePlatformDTO platform, String inboundCallId, String deviceId, String sn,
        List<CascadeCatalogNode> nodes) {
        if (platform == null) {
            return 0;
        }
        int sumNum = Math.max(nodes == null ? 0 : nodes.size(), 1);
        List<List<CascadeCatalogNode>> pages = paginate(nodes);
        /* 不用 defaultIfBlank：它的默认值是急求值的，入站 Call-ID 非空时也会白生成一个 */
        String firstCallId = StringUtils.isNotBlank(inboundCallId) ? inboundCallId : SipRequestUtils.getNewCallId();

        if (!serial || pages.size() <= 1) {
            return sendAll(platform, firstCallId, deviceId, sn, sumNum, pages);
        }

        PageBatch batch = new PageBatch(batchKey(platform, sn), platform, deviceId, sn, sumNum, pages);
        batches.put(batch.key, batch);
        /* 首页必须先注册再发送：上级回 200 OK 可能比本方法的返回值更快到达 */
        inflight.put(firstCallId, batch);
        String firstPage = catalogAssembler.renderCatalogResponse(deviceId, sn, sumNum, pages.get(0));
        String used = cascadeUpperMessenger.send(platform, firstPage, firstCallId,
            okEvent(firstCallId));
        if (used == null) {
            inflight.remove(firstCallId);
            batches.remove(batch.key);
            log.warn("目录首页发送失败，放弃本次下发: platformId={}, sn={}", platform.getPlatformId(), sn);
            return 0;
        }
        batch.cursor = 1;
        batch.inflightAt = System.currentTimeMillis();
        batch.startAt = batch.inflightAt;
        log.info("目录分片开始: platformId={}, sn={}, 总节点={}, 分包={}, 串行=true",
            platform.getPlatformId(), sn, sumNum, pages.size());
        /* 只有一页时 sendCatalog 走上面的快分支，这里 pages.size() >= 2 */
        return completedCount(batch);
    }

    /**
     * 收到 200 OK：推进下一页。
     *
     * <p>由 {@link CascadeUpperMessenger} 的 okEvent 按 Call-ID 回调；与看门狗通过
     * {@code remove(callId)} 的原子性保证同一次推进只发生一次。
     */
    public void onPageAck(String callId) {
        if (StringUtils.isBlank(callId)) {
            return;
        }
        PageBatch batch = inflight.remove(callId);
        if (batch == null) {
            return;
        }
        advance(batch, "收到 200 OK");
    }

    /** 看门狗：超时未收到 200 OK 的分片强制推进，并清理超时批次。 */
    @Scheduled(fixedDelayString = "${gateway.cascade.catalog.scan-interval-ms:1000}")
    public void scanTimeoutPages() {
        if (inflight.isEmpty() && batches.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PageBatch> entry : inflight.entrySet()) {
            if (now - entry.getValue().inflightAt < okTimeoutMs) {
                continue;
            }
            if (inflight.remove(entry.getKey(), entry.getValue())) {
                advance(entry.getValue(), "等待 200 OK 超时 " + (now - entry.getValue().inflightAt) + "ms");
            }
        }
        for (Map.Entry<String, PageBatch> entry : batches.entrySet()) {
            PageBatch batch = entry.getValue();
            if (now - batch.startAt > batchTtlMs) {
                batches.remove(entry.getKey(), batch);
                inflight.values().remove(batch);
                log.warn("目录分片批次超时丢弃: key={}, 已发={}/{}", batch.key, batch.cursor, batch.pages.size());
            }
        }
    }

    /** 推进一页；发送失败则放弃整批（已发出的分片不回滚，上级下次查询会重新拉一遍完整目录）。 */
    private void advance(PageBatch batch, String reason) {
        synchronized (batch) {
            if (batch.finished) {
                return;
            }
            if (batch.cursor >= batch.pages.size()) {
                batch.finished = true;
                batches.remove(batch.key, batch);
                log.info("目录分片完成: platformId={}, sn={}, 分包={}, 末页原因={}",
                    batch.platform.getPlatformId(), batch.sn, batch.pages.size(), reason);
                return;
            }
            int index = batch.cursor;
            String callId = SipRequestUtils.getNewCallId();
            inflight.put(callId, batch);
            batch.inflightAt = System.currentTimeMillis();
            String xml = catalogAssembler.renderCatalogResponse(
                batch.deviceId, batch.sn, batch.sumNum, batch.pages.get(index));
            String used = cascadeUpperMessenger.send(batch.platform, xml, callId, okEvent(callId));
            if (used == null) {
                inflight.remove(callId, batch);
                batch.finished = true;
                batches.remove(batch.key, batch);
                log.warn("目录第 {} 包发送失败，放弃剩余分片: platformId={}, sn={}",
                    index + 1, batch.platform.getPlatformId(), batch.sn);
                return;
            }
            batch.cursor = index + 1;
            log.debug("目录分片推进: sn={}, 页={}/{}, 原因={}", batch.sn, batch.cursor, batch.pages.size(), reason);
        }
    }

    /** 非串行快路径：一次性把全部页发完。 */
    private int sendAll(CascadePlatformDTO platform, String firstCallId, String deviceId, String sn,
        int sumNum, List<List<CascadeCatalogNode>> pages) {
        int sent = 0;
        for (int i = 0; i < pages.size(); i++) {
            String callId = i == 0 ? firstCallId : SipRequestUtils.getNewCallId();
            String xml = catalogAssembler.renderCatalogResponse(deviceId, sn, sumNum, pages.get(i));
            if (cascadeUpperMessenger.send(platform, xml, callId, null) == null) {
                break;
            }
            sent++;
        }
        return sent;
    }

    /**
     * 切页：首包只含域节点（{@code pages[0]}），其余按 {@code batch-size} 切块。
     *
     * <p>首包单独成页是因为上级要靠 {@code ParentID} 建树，根节点必须最先落地；
     * 与后续通道混在一包时，部分上级会按不可解析的父节点直接丢弃整包条目。
     */
    private List<List<CascadeCatalogNode>> paginate(List<CascadeCatalogNode> nodes) {
        List<List<CascadeCatalogNode>> pages = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            pages.add(Collections.emptyList());
            return pages;
        }
        pages.add(nodes.subList(0, 1));
        int batchSize = catalogBatchSize > 0 ? catalogBatchSize : 50;
        for (int offset = 1; offset < nodes.size(); offset += batchSize) {
            pages.add(nodes.subList(offset, Math.min(offset + batchSize, nodes.size())));
        }
        return pages;
    }

    /** 已发页数（首页已发，{@code cursor} 即下一待发下标）。 */
    private int completedCount(PageBatch batch) {
        return batch.cursor;
    }

    /** 按 Call-ID 绑定的成功回调：上级对本页回 2xx 即推进下一页。 */
    private Event okEvent(String callId) {
        return result -> onPageAck(callId);
    }

    private String batchKey(CascadePlatformDTO platform, String sn) {
        return platform.getPlatformId() + "#" + StringUtils.defaultString(sn);
    }

    /** 一次目录下发批次的进度。 */
    private static final class PageBatch {

        final String                       key;
        final CascadePlatformDTO           platform;
        final String                       deviceId;
        final String                       sn;
        final int                          sumNum;
        final List<List<CascadeCatalogNode>> pages;

        /** 下一页待发下标 */
        int     cursor;
        /** 当前在途分片的发出时间（看门狗据此判超时） */
        long    inflightAt;
        /** 批次创建时间（TTL 清理用） */
        long    startAt;
        /** 是否已结束；重入保护放在 synchronized(this) 内 */
        boolean finished;

        PageBatch(String key, CascadePlatformDTO platform, String deviceId, String sn, int sumNum,
            List<List<CascadeCatalogNode>> pages) {
            this.key = key;
            this.platform = platform;
            this.deviceId = deviceId;
            this.sn = sn;
            this.sumNum = sumNum;
            this.pages = pages;
            this.startAt = System.currentTimeMillis();
        }
    }
}
