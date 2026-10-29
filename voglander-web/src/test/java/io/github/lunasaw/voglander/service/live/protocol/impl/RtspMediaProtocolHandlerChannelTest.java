package io.github.lunasaw.voglander.service.live.protocol.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.StreamProxyZlmWrapperService;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.dto.StreamProxyRequest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishContext;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishResult;
import io.github.lunasaw.zlm.config.ZlmNode;

/**
 * {@link RtspMediaProtocolHandler} 地址解析单元测试：通道优先、设备回落、通道缺地址不串流。
 *
 * <p>
 * 三条用例对应 NVR 场景的三个真实后果：点通道 A 必须拉到 A、没通道的存量设备行为不变、
 * 通道缺这一路地址时必须报错而不是默默播另一路。
 * </p>
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RTSP 处理器地址解析")
class RtspMediaProtocolHandlerChannelTest {

    private static final String DEVICE_ID       = "9000003";
    private static final String DEVICE_RTSP_URL = "rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/301";
    private static final String DEVICE_SUB_URL  = "rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/302";
    private static final String CHANNEL_RTSP_URL = "rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/701";
    private static final String CHANNEL_SUB_URL  = "rtsp://admin:pwd@192.168.60.165:554/Streaming/Channels/702";

    @Mock
    private DeviceManager                deviceManager;

    @Mock
    private DeviceChannelManager         deviceChannelManager;

    @Mock
    private StreamProxyZlmWrapperService streamProxyZlmWrapperService;

    @InjectMocks
    private RtspMediaProtocolHandler      handler;

    @BeforeEach
    void setUp() {
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(DEVICE_ID);
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl(DEVICE_RTSP_URL);
        extendInfo.setRtspSubUrl(DEVICE_SUB_URL);
        device.setExtendInfo(extendInfo);
        // lenient：通道地址命中时不会读设备级地址，缺地址的用例也不会发建流请求
        lenient().when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device);
        lenient().when(streamProxyZlmWrapperService.addStreamProxy(any())).thenReturn(null);
    }

    @Test
    @DisplayName("带通道ID：优先用通道自己的地址，而不是设备级地址")
    void establish_shouldPreferChannelUrl() {
        when(deviceChannelManager.getDtoByDeviceId(DEVICE_ID, "7"))
            .thenReturn(channel("7", CHANNEL_RTSP_URL, CHANNEL_SUB_URL));

        handler.establish(context("7", "main"));

        assertEquals(CHANNEL_RTSP_URL, capturedUrl());
    }

    @Test
    @DisplayName("带通道ID + 辅码流：取通道自己的辅地址")
    void establish_shouldPreferChannelSubUrl() {
        when(deviceChannelManager.getDtoByDeviceId(DEVICE_ID, "7"))
            .thenReturn(channel("7", CHANNEL_RTSP_URL, CHANNEL_SUB_URL));

        handler.establish(context("7", "sub"));

        assertEquals(CHANNEL_SUB_URL, capturedUrl());
    }

    @Test
    @DisplayName("通道表里没有该通道（存量单设备）：回落设备级地址，行为不变")
    void establish_shouldFallbackToDeviceUrl() {
        when(deviceChannelManager.getDtoByDeviceId(DEVICE_ID, "7")).thenReturn(null);

        handler.establish(context("7", "main"));

        assertEquals(DEVICE_RTSP_URL, capturedUrl());
    }

    @Test
    @DisplayName("不带通道ID：直接走设备级地址")
    void establish_shouldUseDeviceUrlWhenNoChannel() {
        handler.establish(context(null, "main"));

        assertEquals(DEVICE_RTSP_URL, capturedUrl());
        verify(deviceChannelManager, never()).getDtoByDeviceId(any(), any());
    }

    @Test
    @DisplayName("通道存在但没配辅码流地址：明确失败，绝不回落设备级（否则会播成另一路摄像机）")
    void establish_shouldFailWhenChannelSubUrlMissing() {
        when(deviceChannelManager.getDtoByDeviceId(DEVICE_ID, "7"))
            .thenReturn(channel("7", CHANNEL_RTSP_URL, null));

        MediaEstablishResult result = handler.establish(context("7", "sub"));

        assertFalse(result.isSuccess());
        assertTrue(result.getFailReason().contains("通道[7]"), result.getFailReason());
        assertTrue(result.getFailReason().contains("辅码流"), result.getFailReason());
        verify(streamProxyZlmWrapperService, never()).addStreamProxy(any());
    }

    @Test
    @DisplayName("通道存在但没配主码流地址：同样明确失败，不带出设备级地址")
    void establish_shouldFailWhenChannelMainUrlMissing() {
        when(deviceChannelManager.getDtoByDeviceId(DEVICE_ID, "7"))
            .thenReturn(channel("7", null, CHANNEL_SUB_URL));

        MediaEstablishResult result = handler.establish(context("7", "main"));

        assertFalse(result.isSuccess());
        verify(streamProxyZlmWrapperService, never()).addStreamProxy(any());
    }

    private MediaEstablishContext context(String channelId, String profile) {
        ZlmNode node = new ZlmNode();
        node.setServerId("zlm-1");
        node.setHost("127.0.0.1");
        node.setSecret("zlm");
        return MediaEstablishContext.builder()
            .node(node)
            .streamId("rtsp_live_" + DEVICE_ID)
            .deviceId(DEVICE_ID)
            .channelId(channelId)
            .streamProfile(profile)
            .build();
    }

    private DeviceChannelDTO channel(String channelId, String rtspUrl, String rtspSubUrl) {
        DeviceChannelDTO channel = new DeviceChannelDTO();
        channel.setDeviceId(DEVICE_ID);
        channel.setChannelId(channelId);
        DeviceChannelDTO.ExtendInfo extendInfo = new DeviceChannelDTO.ExtendInfo();
        extendInfo.setRtspUrl(rtspUrl);
        extendInfo.setRtspSubUrl(rtspSubUrl);
        channel.setExtendInfo(extendInfo);
        return channel;
    }

    private String capturedUrl() {
        ArgumentCaptor<StreamProxyRequest> captor = ArgumentCaptor.forClass(StreamProxyRequest.class);
        verify(streamProxyZlmWrapperService).addStreamProxy(captor.capture());
        return captor.getValue().getStreamProxyItem().getUrl();
    }
}
