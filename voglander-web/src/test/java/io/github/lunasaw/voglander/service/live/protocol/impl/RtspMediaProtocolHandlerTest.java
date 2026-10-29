package io.github.lunasaw.voglander.service.live.protocol.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.luna.common.dto.ResultDTOUtils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lunasaw.voglander.common.enums.DeviceProtocolEnum;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.StreamProxyZlmWrapperService;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.dto.StreamProxyDeleteRequest;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.dto.StreamProxyRequest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishContext;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishResult;
import io.github.lunasaw.voglander.service.live.protocol.MediaTerminateContext;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.entity.StreamKey;
import io.github.lunasaw.zlm.entity.StreamProxyItem;

/**
 * RTSP/ONVIF 媒体协议处理器单测：验证"ZLM 主动拉流"模型的协议特定 I/O。
 * <ul>
 * <li>establish = 读设备 extendInfo.rtspUrl + addStreamProxy（app=rtp，流名=streamId），callId 承载代理 key</li>
 * <li>terminate = delStreamProxy（按代理 key；无有效 key 时按 ZLM key 规则兜底，幂等不抛）</li>
 * </ul>
 *
 * @author luna
 */
@DisplayName("RTSP/ONVIF — RtspMediaProtocolHandler 拉流建流/拆流")
@ExtendWith(MockitoExtension.class)
class RtspMediaProtocolHandlerTest {

    private static final String DEVICE_ID = "cam1";
    private static final String STREAM_ID = "rtsp_live_" + DEVICE_ID + "_" + DEVICE_ID;
    private static final String RTSP_URL  = "rtsp://admin:pwd@192.168.1.64:554/Streaming/Channels/101";
    private static final String SUB_URL   = "rtsp://admin:pwd@192.168.1.64:554/Streaming/Channels/102";
    private static final String PROXY_KEY = "__defaultVhost__/rtp/" + STREAM_ID;

    @Mock
    private DeviceManager                      deviceManager;
    @Mock
    private StreamProxyZlmWrapperService       streamProxyZlmWrapperService;

    @InjectMocks
    private RtspMediaProtocolHandler           handler;

    private ZlmNode node() {
        ZlmNode n = new ZlmNode();
        n.setServerId("zlm-1");
        n.setHost("http://10.0.0.5:8082");
        n.setSecret("sec");
        return n;
    }

    private DeviceDTO device(String rtspUrl) {
        return device(rtspUrl, null);
    }

    private DeviceDTO device(String rtspUrl, String rtspSubUrl) {
        DeviceDTO dto = new DeviceDTO();
        dto.setDeviceId(DEVICE_ID);
        DeviceDTO.ExtendInfo ext = new DeviceDTO.ExtendInfo();
        ext.setRtspUrl(rtspUrl);
        ext.setRtspSubUrl(rtspSubUrl);
        dto.setExtendInfo(ext);
        return dto;
    }

    private MediaEstablishContext ctx() {
        return MediaEstablishContext.builder()
            .node(node()).streamId(STREAM_ID).deviceId(DEVICE_ID).build();
    }

    private MediaEstablishContext subCtx() {
        return MediaEstablishContext.builder()
            .node(node()).streamId(STREAM_ID + "_sub").deviceId(DEVICE_ID).streamProfile("sub").build();
    }

    @Test
    @DisplayName("协议声明：支持 RTSP/ONVIF，前缀 rtsp_live_，不要求通道")
    void protocolDeclaration() {
        assertTrue(handler.supportProtocols().contains(DeviceProtocolEnum.RTSP.getType()));
        assertTrue(handler.supportProtocols().contains(DeviceProtocolEnum.ONVIF.getType()));
        assertEquals("rtsp_live_", handler.liveStreamIdPrefix());
        assertFalse(handler.requiresChannel());
    }

    @Test
    @DisplayName("establish → addStreamProxy(app=rtp, stream=streamId, url=设备rtspUrl)，callId=代理key")
    void establish_addsStreamProxy() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL));
        StreamKey key = new StreamKey();
        key.setKey(PROXY_KEY);
        when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(ResultDTOUtils.success(key));

        MediaEstablishResult result = handler.establish(ctx());

        assertTrue(result.isSuccess());
        assertEquals(PROXY_KEY, result.getCallId());
        // 拉流模型无收流端点
        assertNull(result.getRtpPort());
        assertNull(result.getSdpIp());

        ArgumentCaptor<StreamProxyRequest> captor = ArgumentCaptor.forClass(StreamProxyRequest.class);
        verify(streamProxyZlmWrapperService).addStreamProxy(captor.capture());
        StreamProxyRequest request = captor.getValue();
        assertEquals("http://10.0.0.5:8082", request.getHost());
        assertEquals("sec", request.getSecret());
        StreamProxyItem item = request.getStreamProxyItem();
        assertEquals("rtp", item.getApp());
        assertEquals(STREAM_ID, item.getStream());
        assertEquals(RTSP_URL, item.getUrl());
        assertTrue(item.getEnableHls());
        assertFalse(item.getAutoClose());
    }

    @Test
    @DisplayName("establish：设备未配置 rtspUrl → 不发 addStreamProxy，返回 failure")
    void establish_withoutRtspUrl_returnsFailure() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device("  "));

        MediaEstablishResult result = handler.establish(ctx());

        assertFalse(result.isSuccess());
        verify(streamProxyZlmWrapperService, never()).addStreamProxy(any());
    }

    @Test
    @DisplayName("establish：辅码流 → 拉 rtspSubUrl（流名带 _sub）")
    void establish_subProfile_usesSubUrl() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL, SUB_URL));
        StreamKey key = new StreamKey();
        key.setKey(PROXY_KEY + "_sub");
        when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(ResultDTOUtils.success(key));

        MediaEstablishResult result = handler.establish(subCtx());

        assertTrue(result.isSuccess());
        ArgumentCaptor<StreamProxyRequest> captor = ArgumentCaptor.forClass(StreamProxyRequest.class);
        verify(streamProxyZlmWrapperService).addStreamProxy(captor.capture());
        assertEquals(SUB_URL, captor.getValue().getStreamProxyItem().getUrl());
        assertEquals(STREAM_ID + "_sub", captor.getValue().getStreamProxyItem().getStream());
    }

    @Test
    @DisplayName("establish：辅码流未配置 rtspSubUrl → failure 且提示 rtspSubUrl，不发 addStreamProxy")
    void establish_subProfileWithoutSubUrl_returnsFailure() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL, null));

        MediaEstablishResult result = handler.establish(subCtx());

        assertFalse(result.isSuccess());
        assertTrue(result.getFailReason().contains("rtspSubUrl"));
        verify(streamProxyZlmWrapperService, never()).addStreamProxy(any());
    }

    @Test
    @DisplayName("establish：主码流只认 rtspUrl，配了辅地址也不串用")
    void establish_mainProfile_ignoresSubUrl() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL, SUB_URL));
        StreamKey key = new StreamKey();
        key.setKey(PROXY_KEY);
        when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(ResultDTOUtils.success(key));

        handler.establish(ctx());

        ArgumentCaptor<StreamProxyRequest> captor = ArgumentCaptor.forClass(StreamProxyRequest.class);
        verify(streamProxyZlmWrapperService).addStreamProxy(captor.capture());
        assertEquals(RTSP_URL, captor.getValue().getStreamProxyItem().getUrl());
    }

    @Test
    @DisplayName("establish：拉流不带音频轨（设备声明音频却不发数据时会把媒体源注册拖到 10s）")
    void establish_disablesAudioTrack() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL));
        StreamKey key = new StreamKey();
        key.setKey(PROXY_KEY);
        when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(ResultDTOUtils.success(key));

        handler.establish(ctx());

        ArgumentCaptor<StreamProxyRequest> captor = ArgumentCaptor.forClass(StreamProxyRequest.class);
        verify(streamProxyZlmWrapperService).addStreamProxy(captor.capture());
        // 回归护栏：海康 NVR 辅码流 SDP 有音频轨但从不发帧，ZLM 等满 wait_track_ready_ms(10s)
        // 才注册媒体源，注册前 FLV 无输出 → 切辅码流黑屏。关掉音频后随首个视频关键帧注册。
        assertEquals(Boolean.FALSE, captor.getValue().getStreamProxyItem().getEnableAudio());
    }

    @Test
    @DisplayName("establish：addStreamProxy 返回空 → failure")
    void establish_proxyCallFails_returnsFailure() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(RTSP_URL));
        when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(null);

        MediaEstablishResult result = handler.establish(ctx());

        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("terminate → 按 callId(代理key) 删除拉流代理")
    void terminate_deletesByProxyKey() {
        handler.terminate(MediaTerminateContext.builder()
            .node(node()).streamId(STREAM_ID).callId(PROXY_KEY).reason("idle_gc").build());

        ArgumentCaptor<StreamProxyDeleteRequest> captor = ArgumentCaptor.forClass(StreamProxyDeleteRequest.class);
        verify(streamProxyZlmWrapperService).deleteStreamProxy(captor.capture());
        assertEquals(PROXY_KEY, captor.getValue().getProxyKey());
    }

    @Test
    @DisplayName("terminate 幂等：callId 缺失/等于 streamId → 按 ZLM key 规则兜底删除")
    void terminate_missingCallId_fallsBackToRuleKey() {
        handler.terminate(MediaTerminateContext.builder()
            .node(node()).streamId(STREAM_ID).callId(null).reason("establish_failed").build());
        handler.terminate(MediaTerminateContext.builder()
            .node(node()).streamId(STREAM_ID).callId(STREAM_ID).reason("establish_failed").build());

        ArgumentCaptor<StreamProxyDeleteRequest> captor = ArgumentCaptor.forClass(StreamProxyDeleteRequest.class);
        verify(streamProxyZlmWrapperService, org.mockito.Mockito.times(2)).deleteStreamProxy(captor.capture());
        assertEquals(PROXY_KEY, captor.getAllValues().get(0).getProxyKey());
        assertEquals(PROXY_KEY, captor.getAllValues().get(1).getProxyKey());
    }

    @Test
    @DisplayName("terminate 幂等：node 为空直接跳过，不调用 ZLM")
    void terminate_noNode_skips() {
        handler.terminate(MediaTerminateContext.builder()
            .node(null).streamId(STREAM_ID).callId(PROXY_KEY).reason("idle_gc").build());

        verify(streamProxyZlmWrapperService, never()).deleteStreamProxy(any());
    }
}
