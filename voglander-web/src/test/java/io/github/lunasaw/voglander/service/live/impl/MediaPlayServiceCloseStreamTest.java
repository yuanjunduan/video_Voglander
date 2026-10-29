package io.github.lunasaw.voglander.service.live.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.lunasaw.voglander.common.constant.media.MediaSessionConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.MediaSessionDTO;
import io.github.lunasaw.voglander.manager.manager.MediaSessionManager;
import io.github.lunasaw.voglander.repository.cache.redis.RedisLockUtil;
import io.github.lunasaw.voglander.service.live.LiveStreamRegistry;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolHandler;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolRouter;
import io.github.lunasaw.voglander.service.live.protocol.MediaTerminateContext;
import io.github.lunasaw.voglander.service.sse.SseEvent;
import io.github.lunasaw.voglander.service.sse.SseEventBus;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.node.service.NodeService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * D5 红线（PROTOCOL-S5 重构后）：GC 回收必须<b>真实关流</b>，下沉到编排层 {@code closeStream(streamId)}：
 * 解析会话所在节点 → 经 {@link MediaProtocolHandler#terminate}（协议特定 closeRtpServer + sendBye）
 * → 标会话 CLOSED → SSE {@code live.closed} → 清 Registry + 删 pending_close key。
 * <p>
 * S5 后编排层不再直连 GB28181 命令/ZLM RTP，改经 {@link MediaProtocolRouter} 取协议 handler，
 * 真实 closeRtpServer/sendBye 由 {@code Gb28181MediaProtocolHandlerTest} 单独覆盖。本测试只验编排：
 * 解析 handler + 以正确上下文（node/callId/streamId/reason）调 terminate + 幂等收尾。
 * </p>
 *
 * @author luna
 */
@DisplayName("D5 — closeStream 真实关流编排（S5 协议 handler 路由）")
@ExtendWith(MockitoExtension.class)
class MediaPlayServiceCloseStreamTest {

    private static final String  STREAM_ID = "gb_live_dev1_ch1";

    @Mock
    private NodeService          nodeService;
    @Mock
    private MediaSessionManager  mediaSessionManager;
    @Mock
    private LiveStreamRegistry   liveStreamRegistry;
    @Mock
    private MediaProtocolRouter  mediaProtocolRouter;
    @Mock
    private MediaProtocolHandler mediaProtocolHandler;
    @Mock
    private RedisLockUtil        redisLockUtil;
    @Mock
    private SseEventBus          sseEventBus;
    @Mock
    private StringRedisTemplate  stringRedisTemplate;

    @InjectMocks
    private MediaPlayServiceImpl service;

    private MediaSessionDTO session() {
        MediaSessionDTO dto = new MediaSessionDTO();
        dto.setId(42L);
        dto.setStreamId(STREAM_ID);
        dto.setDeviceId("dev1");
        dto.setCallId("call-123");
        dto.setNodeServerId("zlm-1");
        dto.setStatus(MediaSessionConstant.Status.ACTIVE);
        return dto;
    }

    private ZlmNode node() {
        ZlmNode n = new ZlmNode();
        n.setServerId("zlm-1");
        n.setHost("http://10.0.0.5:9092");
        n.setSecret("sec");
        return n;
    }

    @Test
    @DisplayName("closeStream → handler.terminate(node,callId) + 标 CLOSED + SSE live.closed + 清 Registry + 删 pending key")
    void closeStream_realClose() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        when(mediaProtocolRouter.resolveForDevice("dev1")).thenReturn(mediaProtocolHandler);
        when(nodeService.getAvailableNode("zlm-1")).thenReturn(node());

        service.closeStream(STREAM_ID);

        // 1. 协议特定收尾：以正确上下文调 terminate（node + callId + streamId）
        ArgumentCaptor<MediaTerminateContext> ctx = ArgumentCaptor.forClass(MediaTerminateContext.class);
        verify(mediaProtocolHandler).terminate(ctx.capture());
        assertEquals(STREAM_ID, ctx.getValue().getStreamId());
        assertEquals("call-123", ctx.getValue().getCallId());
        assertEquals("zlm-1", ctx.getValue().getNode().getServerId());
        // 2. 标会话 CLOSED
        verify(mediaSessionManager).forceClose(42L);
        // 3. SSE live.closed
        verify(sseEventBus).publish(any(SseEvent.class));
        // 4. 清 Registry
        verify(liveStreamRegistry).remove(STREAM_ID);
        // 5. 删 pending_close key
        verify(stringRedisTemplate).delete(eq("live:pending_close:" + STREAM_ID));
    }

    @Test
    @DisplayName("会话不存在 → 仍清 Registry + 删 pending key（幂等收尾），不触协议收尾，不抛异常")
    void closeStream_noSession_stillCleansUp() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(null);

        service.closeStream(STREAM_ID);

        verify(mediaProtocolHandler, never()).terminate(any());
        verify(liveStreamRegistry).remove(STREAM_ID);
        verify(stringRedisTemplate).delete(eq("live:pending_close:" + STREAM_ID));
    }

    @Test
    @DisplayName("closeStream(reason) → SSE live.closed 的 reason 取传入值，terminate 仍调用")
    void closeStream_withReason_propagatesToSse() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        when(mediaProtocolRouter.resolveForDevice("dev1")).thenReturn(mediaProtocolHandler);
        when(nodeService.getAvailableNode("zlm-1")).thenReturn(node());

        // stream_offline 会先向 ZLM 确认在线性：真实节点不可达会拖慢用例，这里直接钉成"已下线"
        MediaPlayServiceImpl spy = spy(service);
        doReturn(false).when(spy).isStreamAliveInZlm(any(), any());

        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        spy.closeStream(STREAM_ID, "stream_offline");

        verify(mediaProtocolHandler).terminate(any());
        verify(sseEventBus).publish(captor.capture());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) captor.getValue().getData();
        assertEquals("live.closed", captor.getValue().getTopic());
        assertEquals("stream_offline", data.get("reason"));
    }

    @Test
    @DisplayName("迟到下线回调：ZLM 里该流仍在线（切码流后旧代回调）→ 跳过全部收尾，新流不被误关")
    void lateOffline_skippedWhenStreamStillAliveInZlm() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        // 不 stub nodeService：探测被短路后它不该被调用（真调了说明短路失效）

        MediaPlayServiceImpl spy = spy(service);
        doReturn(true).when(spy).isStreamAliveInZlm(any(), any());

        spy.closeStream(STREAM_ID, "stream_offline");

        verify(spy).isStreamAliveInZlm(eq(STREAM_ID), any());
        verify(nodeService, never()).getAvailableNode(any());

        verify(mediaProtocolHandler, never()).terminate(any());
        verify(mediaSessionManager, never()).forceClose(any());
        verify(liveStreamRegistry, never()).remove(any());
        verify(sseEventBus, never()).publish(any());
    }

    @Test
    @DisplayName("真实下线：ZLM 确认已不在线 → 照常收尾（迟到保护不得挡住真下线）")
    void realOffline_stillClosesWhenZlmReportsOffline() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        when(mediaProtocolRouter.resolveForDevice("dev1")).thenReturn(mediaProtocolHandler);
        when(nodeService.getAvailableNode("zlm-1")).thenReturn(node());

        MediaPlayServiceImpl spy = spy(service);
        doReturn(false).when(spy).isStreamAliveInZlm(any(), any());

        spy.closeStream(STREAM_ID, "stream_offline");

        verify(mediaProtocolHandler).terminate(any());
        verify(mediaSessionManager).forceClose(42L);
        verify(liveStreamRegistry).remove(STREAM_ID);
    }

    @Test
    @DisplayName("非 stream_offline 原因（idle_gc / profile_switch / none_reader）→ 不做在线性校验，直接收尾")
    void otherReasons_doNotProbeZlm() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        when(mediaProtocolRouter.resolveForDevice("dev1")).thenReturn(mediaProtocolHandler);
        when(nodeService.getAvailableNode("zlm-1")).thenReturn(node());

        MediaPlayServiceImpl spy = spy(service);

        spy.closeStream(STREAM_ID, "profile_switch");

        verify(spy, never()).isStreamAliveInZlm(any(), any());
        verify(liveStreamRegistry).remove(STREAM_ID);
    }

    @Test
    @DisplayName("无参 closeStream → reason 默认 idle_gc")
    void closeStream_default_reasonIdleGc() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(STREAM_ID)).thenReturn(session());
        when(mediaProtocolRouter.resolveForDevice("dev1")).thenReturn(mediaProtocolHandler);
        when(nodeService.getAvailableNode("zlm-1")).thenReturn(node());

        ArgumentCaptor<SseEvent> captor = ArgumentCaptor.forClass(SseEvent.class);
        service.closeStream(STREAM_ID);

        verify(sseEventBus).publish(captor.capture());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) captor.getValue().getData();
        assertEquals("idle_gc", data.get("reason"));
    }

    @Test
    @DisplayName("关流去重：抢锁失败（已有线程在关）→ 直接短路，不触协议收尾 / 不 forceClose / 不清 Registry")
    void closeStream_lockHeld_skipsAllTeardown() {
        when(redisLockUtil.lock(any(), any(), any())).thenReturn(false);

        service.closeStream(STREAM_ID, "stream_offline");

        verify(mediaSessionManager, never()).getByStreamId(any());
        verify(mediaProtocolHandler, never()).terminate(any());
        verify(mediaSessionManager, never()).forceClose(any());
        verify(liveStreamRegistry, never()).remove(any());
        verify(sseEventBus, never()).publish(any());
        verify(redisLockUtil, never()).unLock(any(), any());
    }
}
