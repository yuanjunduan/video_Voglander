package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import io.github.lunasaw.voglander.common.anno.TechnicalScheduler;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRuntimeSnapshotDTO;

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
 * <p>另一类需求是<b>同步等结论</b>：删除本地资产前必须先向上级取消上报，且只有取消成功才允许删。
 * {@link #trackSync} 登记的条目额外挂一个 future，收到响应即带状态码完成，
 * {@link #awaitDelivery} 据此判定上级是否确认；这类条目不参与后台重发——重发会换 Call-ID，
 * 等待方就再也对不上号了，结论只能由调用方自己决定。
 *
 * @author luna
 */
@Slf4j
@Component
@TechnicalScheduler(category = TechnicalScheduler.Category.PROTOCOL)
public class CascadeNotifyAckTracker {

    /** 后台重发用的待确认表 */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** callId → 上级响应状态码；只有 {@link #trackSync} 登记的条目会出现在这里 */
    private final ConcurrentHashMap<String, CompletableFuture<Integer>> ackFutures = new ConcurrentHashMap<>();

    /** SIP 没有 0 号状态码，用它表达"没有任何响应" */
    public static final int STATUS_NO_RESPONSE = 0;

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
     * 登记一条需要<b>同步等结论</b>的推送。
     *
     * <p>与 {@link #track} 的区别：额外挂一个 future 供等待方取结果，并且<b>不重发</b>
     * （重发会换 Call-ID，等待方对不上号；取消上报的成败由调用方决定后续动作，重发只会拖长删除耗时）。
     *
     * @param callId     本次发送返回的 callId，作为响应匹配键
     * @param platformId 目标上级平台编码
     * @param desc       推送内容描述（写日志用）
     */
    public void trackSync(String callId, String platformId, String desc) {
        if (callId == null) {
            return;
        }
        ackFutures.put(callId, new CompletableFuture<>());
        pending.put(callId, new Pending(platformId, desc, null, false, System.currentTimeMillis()));
    }

    /**
     * 等待这批 callId 的上级响应，总等待时长不超过 {@code timeoutMs}。
     *
     * <p>报文在调用前已全部发出，响应是并发到达的，所以逐个 future 取值用的是<b>同一个截止时刻</b>：
     * 最坏情况等满 {@code timeoutMs}，不会随节点数线性放大。
     *
     * @param callIds    已 {@link #trackSync} 登记的 callId（发送失败的不在其中）
     * @param timeoutMs  等待上限（毫秒）
     * @return callId → SIP 状态码；未收到响应的为 {@link #STATUS_NO_RESPONSE}；本方法不抛异常
     */
    public Map<String, Integer> awaitDelivery(Collection<String> callIds, long timeoutMs) {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        if (callIds == null || callIds.isEmpty()) {
            return statuses;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        for (String callId : callIds) {
            CompletableFuture<Integer> future = ackFutures.remove(callId);
            int status = STATUS_NO_RESPONSE;
            if (future != null) {
                try {
                    status = future.get(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    status = STATUS_NO_RESPONSE;
                } catch (Exception e) {
                    log.warn("等待级联推送确认被中断: callId={}, err={}", callId, e.getMessage());
                    status = STATUS_NO_RESPONSE;
                }
            }
            statuses.put(callId, status);
            /* 结论已交给调用方，后台不再重发这条 */
            pending.remove(callId);
        }
        return statuses;
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
        completeAwaiting(callId, statusCode);
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
                if (entry.retry() == null) {
                    /* 同步等待的条目：以"无响应"结算，由等待方决定怎么处理，不重发 */
                    completeAwaiting(callId, STATUS_NO_RESPONSE);
                    log.warn("级联推送 {}s 未确认（不重发）: platformId={}, desc={}",
                        timeoutMs / 1000, entry.platformId(), entry.desc());
                    continue;
                }
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

    /** 唤醒同步等待方：future 只能被结算一次，后到的响应不会覆盖先出的结论。 */
    private void completeAwaiting(String callId, int statusCode) {
        CompletableFuture<Integer> future = ackFutures.remove(callId);
        if (future != null) {
            future.complete(statusCode);
        }
    }

    /** 待确认的推送条目。 */
    private record Pending(String platformId, String desc, Supplier<String> retry, boolean retried, long sentAt) {
    }

    /**
     * 导出等待确认的推送（只读投影，供运行状态页展示）。
     *
     * @return 按发出时间正序的条目（最早发出、最可能出问题的排前面）
     */
    public List<CascadeRuntimeSnapshotDTO.PendingNotify> snapshotPending() {
        List<CascadeRuntimeSnapshotDTO.PendingNotify> list = new ArrayList<>(pending.size());
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            Pending item = entry.getValue();
            list.add(new CascadeRuntimeSnapshotDTO.PendingNotify(entry.getKey(), item.platformId(), item.desc(),
                item.sentAt(), item.retried()));
        }
        list.sort(Comparator.comparingLong(CascadeRuntimeSnapshotDTO.PendingNotify::sentAt));
        return list;
    }
}
