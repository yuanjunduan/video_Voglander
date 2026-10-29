package io.github.lunasaw.voglander.service.live;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.event.AlarmCreatedEvent;
import io.github.lunasaw.voglander.common.event.NodeExitedEvent;
import io.github.lunasaw.voglander.common.event.StreamOfflineEvent;
import io.github.lunasaw.voglander.common.event.StreamReadyEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.MediaSessionDTO;
import io.github.lunasaw.voglander.manager.manager.MediaSessionManager;
import io.github.lunasaw.voglander.service.sse.SseEvent;
import io.github.lunasaw.voglander.service.sse.SseEventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 直播流 Spring 事件监听器。
 * <p>
 * 消费 integration 层发布的 {@link StreamReadyEvent} / {@link NodeExitedEvent}，
 * 处理首播 future 唤醒、SSE 推送、节点故障流关闭等，
 * 不直接依赖 integration 层 Bean，打破 service↔integration 循环依赖。
 * </p>
 *
 * @author luna
 */
@Slf4j
@Component
public class LiveStreamEventListener {

    /**
     * live.ready 上线幂等键前缀，必须与 {@code MediaPlayServiceImpl.LIVE_READY_NOTIFIED_PREFIX} 一致。
     * <p>
     * ZLM 的 on_stream_changed 对 hls/rtsp/rtmp/fmp4/ts 每个 schema 各回调一次，不去重时
     * 一次点播会推 5 条一模一样的"流上线"。Redis 不可用时降级为照常推送，宁可重复也不能丢。
     * </p>
     */
    private static final String LIVE_READY_NOTIFIED_PREFIX     = "live:ready_notified:";
    /** 幂等窗口（秒）：覆盖多 schema 回调的连发；关流收尾会主动删键，重新点播不受影响。 */
    private static final long   LIVE_READY_NOTIFIED_TTL_SEC    = 60;

    @Autowired
    private LiveStreamRegistry  liveStreamRegistry;
    @Autowired
    private SseEventBus         sseEventBus;
    @Autowired
    private MediaSessionManager mediaSessionManager;
    @Autowired
    private MediaPlayService    mediaPlayService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 流上线：唤醒本节点首播 future + 推 SSE live.ready（同一路流每次上线只推一条）。
     */
    @EventListener
    public void onStreamReady(StreamReadyEvent event) {
        String streamId = event.getStreamId();
        log.info("流上线事件, streamId={}", streamId);
        liveStreamRegistry.completeFuture(streamId);
        if (!markReadyNotified(streamId)) {
            return;
        }
        // 携带 deviceId：前端「设备详情」按 deviceId 过滤事件帧，缺省则无 SIP 信令的拉流设备
        // 永远没有事件可展示（live.* 是其唯一来源）。
        MediaSessionDTO session = lookupSession(streamId);
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("streamId", streamId);
        data.put("deviceId", session == null || session.getDeviceId() == null ? "" : session.getDeviceId());
        data.put("channelId", session == null || session.getChannelId() == null ? "" : session.getChannelId());
        // 前端按 topic|ts|设备字段 去重，缺 ts 时同一路流的键恒定，第二次点播会被整帧丢弃
        data.put("ts", System.currentTimeMillis());
        sseEventBus.publish(new SseEvent("live.ready", data));
    }

    /**
     * 抢占"本路流已推过上线"的标记：抢到返回 true（首次，应推送），已存在返回 false。
     * Redis 异常时返回 true——去重是优化，丢事件是故障。
     */
    private boolean markReadyNotified(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(LIVE_READY_NOTIFIED_PREFIX + streamId, "1", LIVE_READY_NOTIFIED_TTL_SEC, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn("live.ready 去重标记写入失败，按首次推送, streamId={}: {}", streamId, e.getMessage());
            return true;
        }
    }

    /**
     * 节点退出：关闭该节点上所有 ACTIVE 会话，推 SSE live.closed（前端自动重连）。
     */
    @EventListener
    public void onNodeExited(NodeExitedEvent event) {
        String serverId = event.getServerId();
        log.warn("ZLM 节点退出, serverId={}", serverId);
        List<MediaSessionDTO> activeSessions = mediaSessionManager.getActiveSessionsByNode(serverId);
        for (MediaSessionDTO session : activeSessions) {
            try {
                mediaSessionManager.forceClose(session.getId());
                if (session.getStreamId() != null) {
                    liveStreamRegistry.remove(session.getStreamId());
                    sseEventBus.publish(new SseEvent("live.closed",
                        Map.of("streamId", session.getStreamId(),
                            "reason", "node_exited",
                            "deviceId", session.getDeviceId() == null ? "" : session.getDeviceId(),
                            "channelId", session.getChannelId() == null ? "" : session.getChannelId(),
                            "ts", System.currentTimeMillis())));
                }
            } catch (Exception e) {
                log.warn("关闭节点退出会话失败, sessionId={}: {}", session.getId(), e.getMessage());
            }
        }
        log.info("节点退出处理完成, serverId={}, 关闭会话数={}", serverId, activeSessions.size());
    }

    /**
     * 流下线 / 无人观看到点：委托 {@link MediaPlayService#closeStream(String, String)} 做标准 BYE 收尾
     * （closeRtpServer + sendBye + 标 CLOSED + SSE live.closed + 清缓存 + 删 pending key）。
     * <p>
     * 符合 SIP/GB28181：established 对话由平台作为 UAC 主动发 BYE 终止，而非单方面删缓存
     * （否则设备侧对话泄漏、rtp 设备仍推流）。由 ZLM {@code onStreamChanged(regist=false)}
     * 与 {@code onStreamNoneReader} 驱动，覆盖 rtp 直播流（不依赖 StreamProxy 表）。
     * 幂等：{@code closeStream} 内按 streamId 查会话，已 CLOSED / 无会话也尽力收尾，不阻断。
     * </p>
     */
    @EventListener
    public void onStreamOffline(StreamOfflineEvent event) {
        String streamId = event.getStreamId();
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        log.info("流下线事件, streamId={}, serverId={}, reason={}",
            streamId, event.getServerId(), event.getReason());
        mediaPlayService.closeStream(streamId, event.getReason());
    }

    /**
     * 按 streamId 反查会话（取 deviceId/channelId 用）；查询失败不阻断事件推送。
     */
    private MediaSessionDTO lookupSession(String streamId) {
        try {
            return mediaSessionManager.getByStreamId(streamId);
        } catch (Exception e) {
            log.warn("查询直播会话失败, streamId={}: {}", streamId, e.getMessage());
            return null;
        }
    }

    /**
     * 告警新建：推 SSE alarm.new。
     * <p>
     * 同样带 ts：前端按 {@code topic|data.ts|deviceId} 去重，缺 ts 时同一设备连续多次报警
     * 键完全相同，只有第一条能进事件列表。
     * </p>
     */
    @EventListener
    public void onAlarmCreated(AlarmCreatedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("deviceId", event.getDeviceId() != null ? event.getDeviceId() : "");
        data.put("ts", System.currentTimeMillis());
        sseEventBus.publish(new SseEvent("alarm.new", data));
    }
}
