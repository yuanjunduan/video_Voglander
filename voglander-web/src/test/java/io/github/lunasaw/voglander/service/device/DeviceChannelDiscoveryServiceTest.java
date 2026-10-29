package io.github.lunasaw.voglander.service.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.intergration.wrapper.channel.ChannelDiscoverer;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoverResult;
import io.github.lunasaw.voglander.intergration.wrapper.channel.dto.DiscoveredChannel;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.repository.entity.DeviceChannelDO;
import io.github.lunasaw.voglander.service.device.dto.ChannelSyncResultDTO;

/**
 * {@link DeviceChannelDiscoveryService} 单元测试：编排语义。
 *
 * <p>
 * 这里测的不是"能不能连上设备"（那是发现器的责任），而是三条编排契约：
 * 失败不落库、成功才落库、重新同步不冲掉已探测的编码。
 * </p>
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通道发现编排")
class DeviceChannelDiscoveryServiceTest {

    private static final String DEVICE_ID = "9000003";
    private static final String RTSP_URL  = "rtsp://admin:Admin123@192.168.60.165:554/Streaming/Channels/301";

    @Mock
    private DeviceManager           deviceManager;

    @Mock
    private DeviceChannelManager    deviceChannelManager;

    @Mock
    private ChannelDiscoverer       discoverer;

    private DeviceChannelDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new DeviceChannelDiscoveryService();
        ReflectionTestUtils.setField(service, "deviceManager", deviceManager);
        ReflectionTestUtils.setField(service, "deviceChannelManager", deviceChannelManager);
        ReflectionTestUtils.setField(service, "discoverers", List.of(discoverer));
        ReflectionTestUtils.setField(service, "defaultTimeoutMs", 3000);
    }

    @Test
    @DisplayName("设备不存在：抛业务异常，不尝试任何发现")
    void sync_shouldRejectUnknownDevice() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.sync(DEVICE_ID));
    }

    @Test
    @DisplayName("设备ID为空：抛业务异常")
    void sync_shouldRejectBlankDeviceId() {
        assertThrows(ServiceException.class, () -> service.sync("  "));
    }

    @Test
    @DisplayName("GB28181 设备：通道由目录应答维护，直接拒绝并说明原因")
    void sync_shouldRejectGb28181Device() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(1));

        ServiceException e = assertThrows(ServiceException.class, () -> service.sync(DEVICE_ID));

        assertTrue(e.getMessage().contains("不支持主动同步"), e.getMessage());
        verify(deviceChannelManager, never()).batchUpsertWithStatus(anyString(), anyList());
    }

    @Test
    @DisplayName("发现失败：台账保持原样（不落库），失败原因透出")
    void sync_shouldNotPersistOnFailure() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(4));
        when(discoverer.supports(any())).thenReturn(true);
        when(discoverer.discover(any(), anyInt()))
            .thenReturn(DiscoverResult.failure("hikvision", "连接失败：Connection refused"));

        ChannelSyncResultDTO result = service.sync(DEVICE_ID);

        assertFalse(result.isPersisted());
        assertEquals("连接失败：Connection refused", result.getFailureReason());
        assertEquals("hikvision", result.getVendor());
        verify(deviceChannelManager, never()).batchUpsertWithStatus(anyString(), anyList());
    }

    @Test
    @DisplayName("发现成功但设备没有任何通道：不落库也不报错")
    void sync_shouldSkipPersistWhenNoChannel() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(4));
        when(discoverer.supports(any())).thenReturn(true);
        when(discoverer.discover(any(), anyInt()))
            .thenReturn(DiscoverResult.success("hikvision", List.of()));

        ChannelSyncResultDTO result = service.sync(DEVICE_ID);

        assertFalse(result.isPersisted());
        assertEquals(0, result.getDiscovered());
        assertTrue(result.getChannels().isEmpty());
        verify(deviceChannelManager, never()).batchUpsertWithStatus(anyString(), anyList());
    }

    @Test
    @DisplayName("发现成功：按 DISCOVER 来源落库，在线数按通道状态统计")
    void sync_shouldPersistDiscoveredChannels() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(4));
        when(discoverer.supports(any())).thenReturn(true);
        when(discoverer.discover(any(), anyInt())).thenReturn(
            DiscoverResult.success("hikvision", List.of(channel("3", 1), channel("7", 0))));
        when(deviceChannelManager.listDeviceChannelDTO(any(DeviceChannelDO.class))).thenReturn(List.of());
        when(deviceChannelManager.batchUpsertWithStatus(eq(DEVICE_ID), anyList())).thenReturn(2);

        ChannelSyncResultDTO result = service.sync(DEVICE_ID);

        assertTrue(result.isPersisted());
        assertEquals(2, result.getAffected());
        assertEquals(1, result.getOnlineCount());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceChannelDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceChannelManager).batchUpsertWithStatus(eq(DEVICE_ID), captor.capture());
        List<DeviceChannelDTO> persisted = captor.getValue();

        DeviceChannelDTO first = persisted.get(0);
        assertEquals("3", first.getChannelId());
        assertEquals(DEVICE_ID, first.getDeviceId());
        assertEquals(DeviceChannelDiscoveryService.STATUS_SOURCE_DISCOVER, first.getStatusSource());
        assertNotNull(first.getLastSeenTime());
        assertEquals("通道3", first.getName(), "设备没给名字时用通道号兜底，避免列表出现空名称");
        assertEquals(RTSP_URL, first.getExtendInfo().getRtspUrl());
        assertEquals("hikvision", first.getExtendInfo().getVendor());
        assertEquals("192.168.60.2", first.getExtendInfo().getSourceIp());
    }

    @Test
    @DisplayName("重新同步：地址不变时保留上次点播探测出的编码与分辨率")
    void sync_shouldCarryOverProbeResult() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(4));
        when(discoverer.supports(any())).thenReturn(true);
        when(discoverer.discover(any(), anyInt()))
            .thenReturn(DiscoverResult.success("hikvision", List.of(channel("3", 1))));
        when(deviceChannelManager.listDeviceChannelDTO(any(DeviceChannelDO.class)))
            .thenReturn(List.of(existing("3", RTSP_URL, "H265", 1920, 1080)));
        when(deviceChannelManager.batchUpsertWithStatus(eq(DEVICE_ID), anyList())).thenReturn(1);

        service.sync(DEVICE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceChannelDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceChannelManager).batchUpsertWithStatus(eq(DEVICE_ID), captor.capture());

        DeviceChannelDTO.ExtendInfo extendInfo = captor.getValue().get(0).getExtendInfo();
        assertEquals("H265", extendInfo.getVideoCodec());
        assertEquals(1920, extendInfo.getVideoWidth());
        assertEquals(1080, extendInfo.getVideoHeight());
    }

    @Test
    @DisplayName("重新同步：地址变了说明换了通道，旧编码不再沿用")
    void sync_shouldDropProbeResultWhenUrlChanged() {
        when(deviceManager.getDtoByDeviceId(DEVICE_ID)).thenReturn(device(4));
        when(discoverer.supports(any())).thenReturn(true);
        when(discoverer.discover(any(), anyInt()))
            .thenReturn(DiscoverResult.success("hikvision", List.of(channel("3", 1))));
        when(deviceChannelManager.listDeviceChannelDTO(any(DeviceChannelDO.class)))
            .thenReturn(List.of(existing("3", "rtsp://admin:pwd@10.0.0.9:554/Streaming/Channels/999", "H264",
                704, 576)));
        when(deviceChannelManager.batchUpsertWithStatus(eq(DEVICE_ID), anyList())).thenReturn(1);

        service.sync(DEVICE_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeviceChannelDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(deviceChannelManager).batchUpsertWithStatus(eq(DEVICE_ID), captor.capture());

        assertNull(captor.getValue().get(0).getExtendInfo().getVideoCodec(),
            "地址变了说明已是另一路通道，旧编码必须丢弃");
    }

    private DeviceDTO device(int type) {
        DeviceDTO device = new DeviceDTO();
        device.setDeviceId(DEVICE_ID);
        device.setType(type);
        DeviceDTO.ExtendInfo extendInfo = new DeviceDTO.ExtendInfo();
        extendInfo.setRtspUrl(RTSP_URL);
        device.setExtendInfo(extendInfo);
        return device;
    }

    private DiscoveredChannel channel(String channelId, int status) {
        DiscoveredChannel channel = new DiscoveredChannel();
        channel.setChannelId(channelId);
        channel.setStatus(status);
        channel.setRtspUrl(RTSP_URL);
        channel.setSourceIp("192.168.60.2");
        return channel;
    }

    private DeviceChannelDTO existing(String channelId, String rtspUrl, String codec, int width, int height) {
        DeviceChannelDTO dto = new DeviceChannelDTO();
        dto.setChannelId(channelId);
        dto.setDeviceId(DEVICE_ID);
        DeviceChannelDTO.ExtendInfo extendInfo = new DeviceChannelDTO.ExtendInfo();
        extendInfo.setRtspUrl(rtspUrl);
        extendInfo.setVideoCodec(codec);
        extendInfo.setVideoWidth(width);
        extendInfo.setVideoHeight(height);
        dto.setExtendInfo(extendInfo);
        return dto;
    }
}
