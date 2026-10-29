package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 出站推送的送达确认与一次性重发。
 *
 * <p>GB28181 的主动推送（目录变更 / 告警 / 移动位置）是"发了就完"的 MESSAGE，没有事务重传。
 * 上级瞬时不可达（重启、网络抖动）时这一次推送就永久丢失：上级目录与本地不一致、
 * 告警在上级侧缺失，且下一次相同的变更可能很久以后才发生。
 *
 * <p>做法：发送方把 callId 与"重发动作"登记进来，收到该 callId 的 MESSAGE 响应即视为送达并出表；
 * 超时未确认则重发一次，之后无论是否确认都不再重试——宁可少一条，也不能把上级刷成告警风暴。
 *
 * <p>只登记"丢了要命"的推送（当前为目录变更）；告警与移动位置是流式数据，丢掉一条可接受。
 *
 * @author luna
 */
@Slf4j
@Component
public class CascadeNotifyAckTracker {

    /** callId → 待确认推送 */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** 等待上级确认的最长时间（秒） */
    @Value("${gateway.cascade.notify.ack-timeout-sec:10}")
    private long ackTimeoutSec = 10;

    /**
     * 登记一条已发出的推送。
     *
     * @param callId     本次发送返回的 callId，作为响应匹配键
     * @param platformId 目标上级平台编码
     * @param desc       推送内容描述（写日志用）
     * @param retry      重发动作，返回新的 callId；返回 null 表示放弃
     */
    public void track(String callId, String platformId, String desc, Supplier<String> retry) {
        if (callId == null) {
            return;
        }
        pending.put(callId, new Pending(platformId, desc, retry, false, System.currentTimeMillis()));
    }

    /**
     * 收到 MESSAGE 响应：无论 2xx 还是 4xx/5xx 都说明报文已到达上级，不再重发。
     * 4xx/5xx 单独告警——上级收到了但拒绝，通常意味着编码或订阅关系有问题。
     *
     * @param callId     响应归属的 callId
     * @param statusCode SIP 状态码
     */
    public void onResponse(String callId, int statusCode) {
        if (callId == null) {
            return;
        }
        Pending entry = pending.remove(callId);
        if (entry == null) {
            return;
        }
        if (statusCode >= 200 && statusCode < 300) {
            log.debug("级联推送已确认: platformId={}, desc={}, status={}", entry.platformId(), entry.desc(),
                statusCode);
        } else {
            log.warn("级联推送未被上级接受(不再重发): platformId={}, desc={}, status={}", entry.platformId(),
                entry.desc(), statusCode);
        }
    }

    /**
     * 扫描超时未确认的推送并重发一次。
     */
    @Scheduled(fixedDelayString = "${gateway.cascade.notify.ack-scan-interval-ms:5000}")
    public void scanTimeout() {
        long now = System.currentTimeMillis();
        long timeoutMs = Math.max(1L, ackTimeoutSec) * 1000L;
        for (String callId : pending.keySet()) {
            Pending entry = pending.get(callId);
            if (entry == null || now - entry.sentAt() < timeoutMs) {
                continue;
            }
            pending.remove(callId);
            if (entry.retried() || entry.retry() == null) {
                log.warn("级联推送重发后仍无确认，放弃: platformId={}, desc={}", entry.platformId(), entry.desc());
                continue;
            }
            log.warn("级联推送 {}s 未确认，重发一次: platformId={}, desc={}, callId={}",
                timeoutMs / 1000, entry.platformId(), entry.desc(), callId);
            try {
                String newCallId = entry.retry().get();
                if (newCallId != null) {
                    pending.put(newCallId, new Pending(entry.platformId(), entry.desc(), entry.retry(), true, now));
                }
            } catch (Exception e) {
                log.warn("级联推送重发失败，放弃: platformId={}, desc={}, err={}", entry.platformId(), entry.desc(),
                    e.getMessage());
            }
        }
    }

    /** 待确认的推送条目。 */
    private record Pending(String platformId, String desc, Supplier<String> retry, boolean retried, long sentAt) {
    }
}
