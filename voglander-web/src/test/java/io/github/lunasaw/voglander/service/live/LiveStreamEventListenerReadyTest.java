package io.github.lunasaw.voglander.service.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import io.github.lunasaw.voglander.common.event.StreamReadyEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.MediaSessionDTO;
import io.github.lunasaw.voglander.manager.manager.MediaSessionManager;
import io.github.lunasaw.voglander.service.sse.SseEvent;
import io.github.lunasaw.voglander.service.sse.SseEventBus;

/**
 * live.ready 推送红线：必须带 ts、同一路流每次上线只推一条（ZLM 多 schema 回调）、Redis 故障不得丢事件。
 * <p>
 * 前端「实时事件」按 {@code topic|data.ts|设备字段} 去重，缺 ts 时同一路流的键恒定，
 * 第二次点播的 live.ready 会被整帧丢弃——这正是"关掉再点播就再也看不到事件"的成因。
 * </p>
 *
 * @author luna
 */
@DisplayName("live.ready — 带 ts 且每次上线只推一条")
@ExtendWith(MockitoExtension.class)
class LiveStreamEventListenerReadyTest {

    private static final String STREAM_ID = "rtsp_live_hk-nvr165-ch3_7_sub";
    private static final String READY_KEY = "live:ready_notified:" + STREAM_ID;

    @Mock
    private LiveStreamRegistry                          liveStreamRegistry;
    @Mock
    private SseEventBus                                  sseEventBus;
    @Mock
    private MediaSessionManager                          mediaSessionManager;
    @Mock
    private MediaPlayService                             mediaPlayService;
    @Mock
    private StringRedisTemplate                          stringRedisTemplate;
    @Mock
    private ValueOperations<String, String>              valueOperations;

    @InjectMocks
    private LiveStreamEventListener                      listener;

    @Captor
    private ArgumentCaptor<SseEvent>                     eventCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private MediaSessionDTO session() {
        MediaSessionDTO session = new MediaSessionDTO();
        session.setStreamId(STREAM_ID);
        session.setDeviceId("hk-nvr165-ch3");
        session.setChannelId("7");
        return session;
    }

    @Test
    @DisplayName("首次上线 → 推一条 live.ready，payload 含 deviceId/channelId/ts")
    void ready_publishesOnceWithTs() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());

        listener.onStreamReady(new StreamReadyEvent(STREAM_ID));

        verify(sseEventBus).publish(eventCaptor.capture());
        SseEvent event = eventCaptor.getValue();
        assertEquals("live.ready", event.getTopic());
        Map<?, ?> data = assertInstanceOf(Map.class, event.getData());
        assertEquals(STREAM_ID, data.get("streamId"));
        assertEquals("hk-nvr165-ch3", data.get("deviceId"));
        assertEquals("7", data.get("channelId"));
        assertInstanceOf(Long.class, data.get("ts"));
        // 幂等标记必须落在这个键上：关流收尾靠删除同一个键让"重新点播"能再收到 ready
        verify(valueOperations).setIfAbsent(eq(READY_KEY), eq("1"), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("同一路流二次回调（多 schema）→ 不重复推，但仍唤醒首播等待")
    void ready_skipsDuplicateButStillCompletesFuture() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenReturn(false);

        listener.onStreamReady(new StreamReadyEvent(STREAM_ID));

        verify(sseEventBus, never()).publish(any(SseEvent.class));
        verify(liveStreamRegistry).completeFuture(STREAM_ID);
    }

    @Test
    @DisplayName("Redis 故障 → 降级照常推送，不因去重失败丢上线事件")
    void ready_publishesWhenRedisUnavailable() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenThrow(new RedisConnectionFailureException("down"));
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());

        listener.onStreamReady(new StreamReadyEvent(STREAM_ID));

        verify(sseEventBus).publish(eventCaptor.capture());
        assertEquals("live.ready", eventCaptor.getValue().getTopic());
    }

    @Test
    @DisplayName("会话查不到（设备已删）→ 仍推送，deviceId/channelId 落空串而非 NPE")
    void ready_publishesWithBlankIdsWhenSessionMissing() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(null);

        listener.onStreamReady(new StreamReadyEvent(STREAM_ID));

        verify(sseEventBus).publish(eventCaptor.capture());
        Map<?, ?> data = assertInstanceOf(Map.class, eventCaptor.getValue().getData());
        assertEquals("", data.get("deviceId"));
        assertEquals("", data.get("channelId"));
        assertTrue(data.containsKey("ts"));
    }
}
