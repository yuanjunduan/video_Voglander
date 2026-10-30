package io.github.lunasaw.voglander.manager.cascade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sip.ClientTransaction;
import javax.sip.DialogState;
import javax.sip.message.Request;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.gb28181.common.entity.mansrtsp.ManSrtspRequest;
import io.github.lunasaw.gb28181.common.entity.enums.InviteSessionNameEnum;
import io.github.lunasaw.gb28181.common.entity.sdp.GbSessionDescription;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientByeEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientInfoEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientInviteEvent;
import io.github.lunasaw.gbproxy.server.enums.PlayActionEnums;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeMediaInviteListener;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.media.VoglanderServerMediaCommand;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.config.ZlmIntegrationConfig;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.zlm.api.ZlmRestService;
import io.github.lunasaw.zlm.entity.MediaOnlineStatus;
import io.github.lunasaw.zlm.entity.rtp.CloseSendRtpReq;
import gov.nist.javax.sip.SipProviderImpl;
import gov.nist.javax.sip.stack.SIPDialog;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeMediaInviteListener 单元测试")
class CascadeMediaInviteListenerTest {

    @Mock
    CascadeChannelManager       cascadeChannelManager;
    @Mock
    VoglanderServerMediaCommand serverMediaCommand;
    @InjectMocks
    CascadeMediaInviteListener  listener;

    /** SDP 为 null 时，INVITE 应优雅失败（不抛异常） */
    @Test
    @DisplayName("INVITE：SDP 为 null 时不抛异常")
    void invite_null_sdp_no_exception() {
        ClientInviteEvent event = new ClientInviteEvent(this, "call-001", "client-id", null, "ctx-key");
        Assertions.assertDoesNotThrow(() -> listener.onInvite(event));
        verifyNoInteractions(cascadeChannelManager);
    }

    /** BYE 事件：callId 未注册时不抛异常（幂等） */
    @Test
    @DisplayName("BYE：未注册的 callId 应幂等处理")
    void bye_unknown_call_id_no_exception() {
        ClientByeEvent event = new ClientByeEvent(this, "unknown-call", 200);
        Assertions.assertDoesNotThrow(() -> listener.onBye(event));
    }

    /** INFO：PAUSE → controlPlayBack(PLAY_RESUME) */
    @Test
    @DisplayName("INFO：PAUSE 透传 PLAY_RESUME")
    void info_pause_maps_resume_enum() throws Exception {
        seedSession("call-pb", "ssrc-1", "34020000001320000010", "34020000002000000001", true);
        ClientInfoEvent event = infoEvent("34020000002000000001", "PAUSE", null, null);
        listener.onInfo(event);
        verify(serverMediaCommand).controlPlayBack("34020000001320000010", PlayActionEnums.PLAY_RESUME);
    }

    /** INFO：PLAY 无 Range/Scale → controlPlayBack(PLAY_NOW) */
    @Test
    @DisplayName("INFO：PLAY 恢复透传 PLAY_NOW")
    void info_play_resume_maps_play_now() throws Exception {
        seedSession("call-pb", "ssrc-1", "34020000001320000010", "34020000002000000001", true);
        ClientInfoEvent event = infoEvent("34020000002000000001", "PLAY", null, null);
        listener.onInfo(event);
        verify(serverMediaCommand).controlPlayBack("34020000001320000010", PlayActionEnums.PLAY_NOW);
    }

    /** INFO：PLAY + Scale → controlPlayBack(PLAY_SPEED, scale) */
    @Test
    @DisplayName("INFO：倍速透传 PLAY_SPEED + 倍率值")
    void info_scale_maps_play_speed_with_value() throws Exception {
        seedSession("call-pb", "ssrc-1", "34020000001320000010", "34020000002000000001", true);
        ClientInfoEvent event = infoEvent("34020000002000000001", "PLAY", null, 2.0d);
        listener.onInfo(event);
        verify(serverMediaCommand).controlPlayBack("34020000001320000010", PlayActionEnums.PLAY_SPEED, 2.0d);
    }

    /** INFO：PLAY + Range(npt=30-) → controlPlayBack(PLAY_RANGE, 30L) */
    @Test
    @DisplayName("INFO：拖动透传 PLAY_RANGE + seek 秒")
    void info_range_maps_play_range_with_seconds() throws Exception {
        seedSession("call-pb", "ssrc-1", "34020000001320000010", "34020000002000000001", true);
        ClientInfoEvent event = infoEvent("34020000002000000001", "PLAY", "npt=30-", null);
        listener.onInfo(event);
        verify(serverMediaCommand).controlPlayBack("34020000001320000010", PlayActionEnums.PLAY_RANGE, 30L);
    }

    /** INFO：找不到活跃会话时不抛异常、不透传 */
    @Test
    @DisplayName("INFO：无活跃会话不透传")
    void info_no_session_no_forward() {
        ClientInfoEvent event = infoEvent("unknown-channel", "PAUSE", null, null);
        Assertions.assertDoesNotThrow(() -> listener.onInfo(event));
        verifyNoInteractions(serverMediaCommand);
    }

    /** 录像下载会话（s=Download）：不响应 MANSRTSP 控制，否则单向取流永远不会被空闲回收 */
    @Test
    @DisplayName("INFO：下载会话忽略回放控制")
    void info_download_session_ignored() throws Exception {
        seedDownloadSession("call-dl", "0987654321", "hk-nvr165-ch3", "34020000001320000007");
        ClientInfoEvent event = infoEvent("34020000001320000007", "PAUSE", null, null);

        Assertions.assertDoesNotThrow(() -> listener.onInfo(event));
        verifyNoInteractions(serverMediaCommand);
    }

    /** 语音对讲（s=Talk）：显式回 488，且不去拉实时视频流 */
    @Test
    @DisplayName("INVITE：语音对讲回 488 且不拉流")
    void invite_talk_rejected_without_pull() throws Exception {
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(anyString(), anyString()))
            .thenReturn(channel("7"));
        GbSessionDescription sdp = new GbSessionDescription(null);
        sdp.setSessionType(InviteSessionNameEnum.TALK);
        sdp.setAddress("127.0.0.1");
        sdp.setPort(20000);
        ClientInviteEvent event = new ClientInviteEvent(this, "call-talk", "34020000001320000007", sdp, "ctx-talk");

        Assertions.assertDoesNotThrow(() -> listener.onInvite(event));
        verifyNoInteractions(serverMediaCommand);
    }

    /** Range 解析：npt=10.5-30 → 10 秒 */
    @Test
    @DisplayName("parseRangeSeconds：解析起始秒")
    void parse_range_seconds() {
        Assertions.assertEquals(10L, CascadeMediaInviteListener.parseRangeSeconds("npt=10.5-30"));
        Assertions.assertEquals(30L, CascadeMediaInviteListener.parseRangeSeconds("30-"));
        Assertions.assertNull(CascadeMediaInviteListener.parseRangeSeconds(""));
        Assertions.assertNull(CascadeMediaInviteListener.parseRangeSeconds("npt=-"));
    }

    // ============================ helpers ============================

    /** 扫描：超过 ACK 超时的待推流会话被回收（端口归还） */
    @Test
    @DisplayName("扫描：超过 ACK 超时的会话被回收")
    void scan_expires_pending() throws Exception {
        seedPendingSession("call-pending", 30001);
        setListenerField("optimisticPushDelayMs", 0L); // 关闭兜底，只走超时分支
        setListenerField("ackTimeoutSec", 0L);

        listener.cleanExpiredPending();

        Assertions.assertTrue(pendingSessions().isEmpty());
        Assertions.assertTrue(activeSessions().isEmpty());
    }

    /** 扫描：兜底推流在无 ZLM 配置时优雅失败，会话从 pending 摘除且不残留 active */
    @Test
    @DisplayName("扫描：兜底推流无 ZLM 时优雅失败")
    void scan_optimistic_push_without_zlm() throws Exception {
        seedPendingSession("call-pending-2", 30002);
        setListenerField("optimisticPushDelayMs", 1L);
        Thread.sleep(5);

        Assertions.assertDoesNotThrow(() -> listener.cleanExpiredPending());
        Assertions.assertTrue(pendingSessions().isEmpty());
        Assertions.assertTrue(activeSessions().isEmpty());
    }

    // ============================ SSRC 兜底 ============================

    /** 上级带了 ssrc：去掉首尾空白后原样回显，不重新生成 */
    @Test
    @DisplayName("ssrc：上级已携带则原样回显")
    void ssrc_echoUpstream() throws Exception {
        GbSessionDescription sdp = new GbSessionDescription(null);
        sdp.setSsrc(" 0987654321 ");

        Assertions.assertEquals("0987654321", resolveSsrc(sdp, channel("7"), false));
    }

    /** 上级未带 ssrc：生成 10 位非 0 值，实时光/回放分别以 0/1 开头 */
    @Test
    @DisplayName("ssrc：上级未携带时生成 10 位非 0 值")
    void ssrc_generateWhenMissing() throws Exception {
        GbSessionDescription sdp = new GbSessionDescription(null);
        sdp.setSsrc(null);

        String live     = resolveSsrc(sdp, channel("7"), false);
        String playback = resolveSsrc(sdp, channel("7"), true);

        Assertions.assertEquals(10, live.length());
        Assertions.assertTrue(live.startsWith("0"));
        Assertions.assertNotEquals("0", live);
        Assertions.assertTrue(playback.startsWith("1"));
    }

    /** 兜底开关关闭：退回旧行为填 "0"（便于线上快速回滚） */
    @Test
    @DisplayName("ssrc：兜底关闭时回落 0")
    void ssrc_fallbackDisabled() throws Exception {
        setListenerField("ssrcFallbackEnabled", false);
        GbSessionDescription sdp = new GbSessionDescription(null);
        sdp.setSsrc("");

        Assertions.assertEquals("0", resolveSsrc(sdp, channel("7"), false));
    }

    // ============================ active 会话回收 ============================

    /** 源流已从 ZLM 消失：停推流并移出 active（端口随之释放） */
    @Test
    @DisplayName("回收：ZLM 查无源流 → stopSendRtp 并移除会话")
    void reap_sourceStreamGone_stopsPush() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 0L);
        setListenerField("activeIdleTimeoutSec", 10000L);
        seedSession("call-dead", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            zlm.when(() -> ZlmRestService.isMediaOnline(any(), any(), any(Map.class)))
                .thenReturn(online(false));

            listener.reapStaleActiveSessions();

            zlm.verify(() -> ZlmRestService.stopSendRtp(
                eq("http://127.0.0.1:8080"), eq("sec"), any(CloseSendRtpReq.class)));
        }

        Assertions.assertTrue(activeSessions().isEmpty());
    }

    /** 探活异常：保守保留，宁可漏收一轮也不误杀正常流 */
    @Test
    @DisplayName("回收：探活异常保守保留会话")
    void reap_probeThrows_keepsSession() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 0L);
        setListenerField("activeIdleTimeoutSec", 10000L);
        seedSession("call-jitter", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            zlm.when(() -> ZlmRestService.isMediaOnline(any(), any(), any(Map.class)))
                .thenThrow(new RuntimeException("network jitter"));

            listener.reapStaleActiveSessions();

            zlm.verify(() -> ZlmRestService.stopSendRtp(any(), any(), any(CloseSendRtpReq.class)), never());
        }

        Assertions.assertTrue(activeSessions().containsKey("call-jitter"));
    }

    /** 源流在线但长时间无活动：命中空闲超时后被回收（对齐 CSS 的 600s 兜底） */
    @Test
    @DisplayName("回收：空闲超时 → stopSendRtp 并移除会话")
    void reap_idleTimeout_stopsPush() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 0L);
        setListenerField("activeIdleTimeoutSec", 0L);
        seedSession("call-idle", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            zlm.when(() -> ZlmRestService.isMediaOnline(any(), any(), any(Map.class)))
                .thenReturn(online(true));

            listener.reapStaleActiveSessions();

            zlm.verify(() -> ZlmRestService.stopSendRtp(
                eq("http://127.0.0.1:8080"), eq("sec"), any(CloseSendRtpReq.class)));
        }

        Assertions.assertTrue(activeSessions().isEmpty());
    }

    /** 宽限期内：刚建立的会话不参与对账，避免与首播窗口竞态 */
    @Test
    @DisplayName("回收：宽限期内不探活")
    void reap_withinGrace_skipsProbe() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 300L);
        seedSession("call-fresh", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            listener.reapStaleActiveSessions();

            zlm.verifyNoInteractions();
        }

        Assertions.assertTrue(activeSessions().containsKey("call-fresh"));
    }

    // ============================ 向上级发 BYE（附录 M） ============================

    /** 源流消失是本端主动收尾：上级只会「收不到 RTP」，必须发 BYE 释放它侧的会话 */
    @Test
    @DisplayName("BYE：源流消失时向上级发 BYE")
    void reap_sourceGone_sendsBye() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 0L);
        setListenerField("activeIdleTimeoutSec", 10000L);
        seedSession("call-bye", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);
        SIPDialog dialog = confirmedDialog();
        setSessionDialog("call-bye", dialog);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            zlm.when(() -> ZlmRestService.isMediaOnline(any(), any(), any(Map.class))).thenReturn(online(false));

            listener.reapStaleActiveSessions();
        }

        verify(dialog).sendRequest(any(ClientTransaction.class));
        Assertions.assertTrue(activeSessions().isEmpty());
    }

    /** 上级发来的 BYE：只回收本地会话，绝不能回 BYE（会变成一来一回的死循环） */
    @Test
    @DisplayName("BYE：收到上级 BYE 时不回发 BYE")
    void onBye_neverSendsBye() throws Exception {
        seedSession("call-upper-bye", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);
        SIPDialog dialog = confirmedDialog();
        setSessionDialog("call-upper-bye", dialog);

        listener.onBye(new ClientByeEvent(this, "call-upper-bye", 200));

        verify(dialog, never()).sendRequest(any(ClientTransaction.class));
        Assertions.assertTrue(activeSessions().isEmpty());
    }

    /** 对话还没 CONFIRMED（2xx 未收到 ACK）：没有可用对话，只能放弃发 BYE 并把会话收尾 */
    @Test
    @DisplayName("BYE：对话未确认时跳过发送")
    void reap_dialogNotConfirmed_skipsBye() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        setListenerField("probeGraceSec", 0L);
        setListenerField("activeIdleTimeoutSec", 10000L);
        seedSession("call-early", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);
        SIPDialog dialog = confirmedDialog();
        when(dialog.getState()).thenReturn(DialogState.EARLY);
        setSessionDialog("call-early", dialog);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            zlm.when(() -> ZlmRestService.isMediaOnline(any(), any(), any(Map.class))).thenReturn(online(false));

            listener.reapStaleActiveSessions();
        }

        verify(dialog, never()).sendRequest(any(ClientTransaction.class));
        Assertions.assertTrue(activeSessions().isEmpty());
    }

    // ============================ 容器关闭 ============================

    /** 进程关闭：全部推流停掉、待推流会话清空，避免 ZLM 侧残留发送器 */
    @Test
    @DisplayName("关闭：销毁时停止全部推流并清空会话")
    void destroy_stopsAllSessions() throws Exception {
        setListenerField("zlmConfig", zlmConfig());
        seedSession("call-a", "0987654321", "hk-nvr165-ch3", "34020000001320000007", false);
        seedSession("call-b", "0987654322", "hk-nvr165-ch4", "34020000001320000008", false);
        seedPendingSession("call-p", 30009);

        try (MockedStatic<ZlmRestService> zlm = mockStatic(ZlmRestService.class)) {
            listener.destroy();

            zlm.verify(() -> ZlmRestService.stopSendRtp(
                eq("http://127.0.0.1:8080"), eq("sec"), any(CloseSendRtpReq.class)), times(2));
        }

        Assertions.assertTrue(activeSessions().isEmpty());
        Assertions.assertTrue(pendingSessions().isEmpty());
    }

    private ClientInfoEvent infoEvent(String userId, String method, String range, Double scale) {
        ManSrtspRequest rtsp = new ManSrtspRequest();
        rtsp.setMethod(method);
        rtsp.setRange(range);
        rtsp.setScale(scale);
        return new ClientInfoEvent(this, userId, "raw-body", "Application/MANSRTSP", rtsp);
    }

    /** 反射调用私有 {@code resolveSsrc}，验证「上级优先、缺失则生成」。 */
    private String resolveSsrc(GbSessionDescription sdp, CascadeChannelDTO channel, boolean playback)
        throws Exception {
        Method m = CascadeMediaInviteListener.class.getDeclaredMethod("resolveSsrc",
            GbSessionDescription.class, CascadeChannelDTO.class, boolean.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(listener, sdp, channel, playback, "call-ssrc");
    }

    /** 组一条级联通道映射（通道 7 → 34020000001320000007）。 */
    private CascadeChannelDTO channel(String localChannelId) {
        CascadeChannelDTO ch = new CascadeChannelDTO();
        ch.setPlatformId("37000020002000000100");
        ch.setLocalDeviceId("hk-nvr165-ch3");
        ch.setLocalChannelId(localChannelId);
        ch.setCascadeChannelId("34020000001320000007");
        ch.setCascadeName("通道7-测试");
        return ch;
    }

    /** 组一份带默认节点的 ZLM 配置（stopSendRtp / isMediaOnline 的 host 来源）。 */
    private ZlmIntegrationConfig zlmConfig() {
        ZlmIntegrationConfig.ZlmServerConfig node = new ZlmIntegrationConfig.ZlmServerConfig();
        node.setServerId("zlm-1");
        node.setHost("http://127.0.0.1");
        node.setPort(8080);
        node.setSecret("sec");
        ZlmIntegrationConfig cfg = new ZlmIntegrationConfig();
        cfg.setServers(List.of(node));
        return cfg;
    }

    private MediaOnlineStatus online(Boolean value) {
        MediaOnlineStatus status = new MediaOnlineStatus();
        status.setOnline(value);
        return status;
    }

    /** 反射注入一条活跃会话到 activeSessions。 */
    private void seedSession(String callId, String ssrc, String localDeviceId, String upperUserId, boolean playback)
        throws Exception {
        activeSessions().put(callId, newSession(ssrc, localDeviceId, upperUserId, playback, false, 30000));
    }

    /** 注入一条录像下载会话（s=Download）。 */
    private void seedDownloadSession(String callId, String ssrc, String localDeviceId, String upperUserId)
        throws Exception {
        activeSessions().put(callId, newSession(ssrc, localDeviceId, upperUserId, true, true, 30001));
    }

    /** 反射注入一条"已应答、等 ACK"的会话到 pendingSessions。 */
    private void seedPendingSession(String callId, int srcPort) throws Exception {
        pendingSessions().put(callId, newSession("ssrc-p", "34020000001320000010",
            "34020000002000000001", false, false, srcPort));
    }

    /** 会话上下文含推流参数（ACK 后据此 startSendRtp），构造签名随之扩展。 */
    private Object newSession(String ssrc, String localDeviceId, String upperUserId, boolean playback,
        boolean download, int srcPort) throws Exception {
        Class<?> sessionClass = Class.forName(
            "io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeMediaInviteListener$MediaSession");
        Constructor<?> ctor = sessionClass.getDeclaredConstructor(
            String.class, String.class, String.class, boolean.class, boolean.class,
            String.class, String.class, String.class, int.class, boolean.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(ssrc, localDeviceId, upperUserId, playback, download,
            "rtp", localDeviceId + "_" + upperUserId, "127.0.0.1", 10000, true, srcPort);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> sessions(String fieldName) throws Exception {
        java.lang.reflect.Field field = CascadeMediaInviteListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (java.util.Map<String, Object>) field.get(listener);
    }

    private java.util.Map<String, Object> activeSessions() throws Exception {
        return sessions("activeSessions");
    }

    private java.util.Map<String, Object> pendingSessions() throws Exception {
        return sessions("pendingSessions");
    }

    private void setListenerField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = CascadeMediaInviteListener.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(listener, value);
    }

    /** 一个已确认的 UAS 对话：构 BYE 走它，{@code getSipProvider()} 由 {@code DialogExt} 提供。 */
    private SIPDialog confirmedDialog() throws Exception {
        SIPDialog dialog = mock(SIPDialog.class);
        Request bye = mock(Request.class);
        SipProviderImpl provider = mock(SipProviderImpl.class);
        ClientTransaction transaction = mock(ClientTransaction.class);
        when(dialog.getState()).thenReturn(DialogState.CONFIRMED);
        when(dialog.createRequest(Request.BYE)).thenReturn(bye);
        when(dialog.getSipProvider()).thenReturn(provider);
        when(provider.getNewClientTransaction(bye)).thenReturn(transaction);
        return dialog;
    }

    /** 把对话挂到指定会话上（对等 {@code sendInviteOk} 里抓取 UAS 对话那一步）。 */
    private void setSessionDialog(String callId, SIPDialog dialog) throws Exception {
        Object session = activeSessions().get(callId);
        java.lang.reflect.Field field = session.getClass().getDeclaredField("dialog");
        field.setAccessible(true);
        field.set(session, dialog);
    }
}
