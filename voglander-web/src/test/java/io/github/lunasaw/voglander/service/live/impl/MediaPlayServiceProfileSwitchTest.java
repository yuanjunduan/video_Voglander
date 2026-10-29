package io.github.lunasaw.voglander.service.live.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.github.lunasaw.voglander.common.constant.media.MediaSessionConstant;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.manager.domaon.dto.MediaSessionDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.MediaSessionManager;
import io.github.lunasaw.voglander.repository.cache.redis.RedisLockUtil;
import io.github.lunasaw.voglander.service.live.LiveSessionInfo;
import io.github.lunasaw.voglander.service.live.LiveStreamRegistry;
import io.github.lunasaw.voglander.service.live.dto.LiveStartDTO;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolHandler;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolRouter;
import io.github.lunasaw.voglander.service.sse.SseEventBus;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.node.NodeSupplier;
import io.github.lunasaw.zlm.node.service.NodeService;

/**
 * 码流切换（main / sub）：streamId 编码 + "切换即替换"回收语义。
 *
 * <ul>
 * <li>sub 的 streamId 追加 {@code _sub}，main 不加后缀（存量流名不变）——它是会话复用键，
 * 不区分码流就会被 ACTIVE 分支直接复用，切换永远无效。</li>
 * <li>{@code replacePrevious=true} 且另一档无人观看（refCount ≤ 1）→ 立即 closeStream 真停。</li>
 * <li>{@code replacePrevious=true} 但另一档有其他观看者（refCount &gt; 1）→ 整单拒绝，不踢人。</li>
 * </ul>
 *
 * @author luna
 */
@DisplayName("码流切换 — streamId 编码 / 切换即替换")
@ExtendWith(MockitoExtension.class)
class MediaPlayServiceProfileSwitchTest {

    private static final String DEVICE_ID = "dev1";
    private static final String MAIN_SID  = "rtsp_live_dev1";
    private static final String SUB_SID   = "rtsp_live_dev1_sub";
    private static final String SERVER_ID = "zlm-1";

    @Mock
    private NodeService                 nodeService;
    @Mock
    private NodeSupplier                nodeSupplier;
    @Mock
    private MediaSessionManager         mediaSessionManager;
    @Mock
    private LiveStreamRegistry          liveStreamRegistry;
    @Mock
    private MediaProtocolRouter         mediaProtocolRouter;
    @Mock
    private RedisLockUtil               redisLockUtil;
    @Mock
    private SseEventBus                 sseEventBus;
    @Mock
    private StringRedisTemplate         stringRedisTemplate;
    @Mock
    private DeviceChannelManager        deviceChannelManager;
    @Mock
    private MediaProtocolHandler        handler;

    @InjectMocks
    private MediaPlayServiceImpl        service;

    private LiveStartDTO dto(String profile, boolean replacePrevious) {
        LiveStartDTO dto = new LiveStartDTO();
        dto.setDeviceId(DEVICE_ID);
        dto.setStreamProfile(profile);
        dto.setReplacePrevious(replacePrevious);
        return dto;
    }

    private LiveSessionInfo activeInfo() {
        LiveSessionInfo info = new LiveSessionInfo();
        info.setNodeServerId(SERVER_ID);
        info.setStatus(MediaSessionConstant.Status.ACTIVE);
        return info;
    }

    private MediaSessionDTO sessionRow() {
        MediaSessionDTO row = new MediaSessionDTO();
        row.setId(1L);
        row.setDeviceId(DEVICE_ID);
        row.setStreamId(MAIN_SID);
        row.setNodeServerId(SERVER_ID);
        row.setCallId("call-1");
        return row;
    }

    private ZlmNode node() {
        ZlmNode n = new ZlmNode();
        n.setServerId(SERVER_ID);
        n.setHost("http://10.0.0.5:8082");
        n.setSecret("sec");
        return n;
    }

    /** 拉流类处理器：无通道、前缀 rtsp_live_，建流阶段故意失败以便流程尽早收束。 */
    private void stubRtspHandler() {
        when(mediaProtocolRouter.resolveForDevice(DEVICE_ID)).thenReturn(handler);
        when(handler.liveStreamIdPrefix()).thenReturn("rtsp_live_");
        when(handler.requiresChannel()).thenReturn(false);
    }

    private void stubStartLock(String streamId) {
        when(redisLockUtil.generateLockValue()).thenReturn("v1");
        when(redisLockUtil.tryLock(any(), any(), any(), any())).thenReturn(true);
    }

    /** 无节点可用：让 startLive 在替换动作之后立即以 LIVE_NODE_UNAVAILABLE 收束。 */
    private void stubNoNode() {
        when(nodeService.selectNode()).thenReturn(null);
        when(nodeSupplier.getNodes()).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("sub 请求 → 查的是带 _sub 的 streamId；main 请求 → 查无后缀的 streamId")
    void streamIdEncodesProfile() {
        stubRtspHandler();
        stubStartLock(SUB_SID);
        stubNoNode();
        when(liveStreamRegistry.getSession(SUB_SID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.startLive(dto("sub", false)));
        verify(liveStreamRegistry).getSession(SUB_SID);
    }

    @Test
    @DisplayName("主码流 streamId 不带 _sub（存量流名/播放地址不变）")
    void mainProfileKeepsLegacyStreamId() {
        stubRtspHandler();
        stubStartLock(MAIN_SID);
        stubNoNode();
        when(liveStreamRegistry.getSession(MAIN_SID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.startLive(dto("main", false)));
        verify(liveStreamRegistry).getSession(MAIN_SID);
        verify(liveStreamRegistry, never()).getSession(SUB_SID);
    }

    @Test
    @DisplayName("切换即替换：另一档仅自己观看（ref=1）→ 立即关流（terminate 被调用）")
    void switchProfile_recyclesOtherProfileWhenNoOtherViewer() {
        String newSid = SUB_SID;
        String oldSid = MAIN_SID;
        stubRtspHandler();
        stubStartLock(newSid);
        stubNoNode();
        when(liveStreamRegistry.getSession(oldSid)).thenReturn(activeInfo());
        when(liveStreamRegistry.getRef(oldSid)).thenReturn(1L);
        // closeStream 内部链路
        when(redisLockUtil.lock(any(), any(), anyInt())).thenReturn(true);
        when(mediaSessionManager.getByStreamId(oldSid)).thenReturn(sessionRow());
        when(nodeService.getAvailableNode(SERVER_ID)).thenReturn(node());
        when(liveStreamRegistry.getSession(newSid)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.startLive(dto("sub", true)));

        verify(handler).terminate(argThat(ctx -> ctx != null && oldSid.equals(ctx.getStreamId())));
        verify(mediaSessionManager).forceClose(1L);
        verify(liveStreamRegistry).remove(oldSid);
    }

    @Test
    @DisplayName("切换即替换：另一档有其他观看者（ref=2）→ 拒绝切换，不关别人的流")
    void switchProfile_rejectedWhenOtherProfileHasViewers() {
        stubRtspHandler();
        stubStartLock(SUB_SID);
        when(liveStreamRegistry.getSession(MAIN_SID)).thenReturn(activeInfo());
        when(liveStreamRegistry.getRef(MAIN_SID)).thenReturn(2L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.startLive(dto("sub", true)));

        assertTrue(ex.getMessage().contains("其他用户"));
        verify(handler, never()).terminate(any());
        verify(mediaSessionManager, never()).forceClose(any());
    }

    @Test
    @DisplayName("未声明 replacePrevious → 不回收另一档（保持既有并行语义）")
    void noReplaceFlag_keepsOtherProfile() {
        stubRtspHandler();
        stubStartLock(SUB_SID);
        stubNoNode();
        when(liveStreamRegistry.getSession(SUB_SID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.startLive(dto("sub", false)));

        verify(liveStreamRegistry, never()).getSession(MAIN_SID);
        verify(handler, never()).terminate(any());
    }

    @Test
    @DisplayName("另一档不是 ACTIVE（如已 CLOSED）→ 跳过替换")
    void nonActiveOtherProfile_isSkipped() {
        stubRtspHandler();
        stubStartLock(SUB_SID);
        stubNoNode();
        LiveSessionInfo closed = new LiveSessionInfo();
        closed.setStatus(MediaSessionConstant.Status.CLOSED);
        when(liveStreamRegistry.getSession(MAIN_SID)).thenReturn(closed);
        when(liveStreamRegistry.getSession(SUB_SID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.startLive(dto("sub", true)));

        verify(handler, never()).terminate(any());
        assertEquals(MediaSessionConstant.Status.CLOSED, closed.getStatus());
    }
}
