package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.gb28181.common.entity.notify.DeviceBroadcastNotify;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeBroadcastHandler;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeUpperMessenger;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;

/**
 * {@link CascadeBroadcastHandler} 单元测试（§9.12）。
 *
 * <p>上级发广播通知后必须收到 {@code Response/Broadcast}：目标认不出来时回 {@code ERROR}
 * 而不是沉默（沉默会让上级一直重试到超时，也不知道是哪一步失败）。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeBroadcastHandler 单元测试")
class CascadeBroadcastHandlerTest {

    private static final String PLATFORM_ID = "37000020002000000100";
    private static final String LOCAL_ID    = "34020000002000000001";
    private static final String CHANNEL_ID  = "34020000001320000007";

    @Mock CascadeChannelManager  cascadeChannelManager;
    @Mock CascadeUpperMessenger  upperMessenger;
    @InjectMocks CascadeBroadcastHandler handler;

    private CascadePlatformDTO platform() {
        CascadePlatformDTO p = new CascadePlatformDTO();
        p.setPlatformId(PLATFORM_ID);
        p.setLocalClientId(LOCAL_ID);
        p.setLocalIp("192.168.118.7");
        return p;
    }

    private DeviceBroadcastNotify notify(String targetId) {
        DeviceBroadcastNotify notify = new DeviceBroadcastNotify();
        notify.setCmdType("Broadcast");
        notify.setSn("992");
        notify.setSourceId("37000000001360000001");
        notify.setTargetId(targetId);
        return notify;
    }

    private String captureXml() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(upperMessenger).send(any(CascadePlatformDTO.class), captor.capture(), isNull(), isNull());
        return captor.getValue();
    }

    @Test
    @DisplayName("目标为本级平台编码：回 OK")
    void targetIsLocalClient_returnsOk() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), isNull(), isNull())).thenReturn("call-1");

        assertTrue(handler.handle(platform(), notify(LOCAL_ID)));

        String xml = captureXml();
        assertTrue(xml.contains("<CmdType>Broadcast</CmdType>"), xml);
        assertTrue(xml.contains("<Result>OK</Result>"), xml);
        assertTrue(xml.contains("<DeviceID>" + LOCAL_ID + "</DeviceID>"), xml);
        assertTrue(xml.contains("<SN>992</SN>"), xml);
    }

    @Test
    @DisplayName("目标为已登记级联通道：回 OK")
    void targetIsCascadeChannel_returnsOk() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), isNull(), isNull())).thenReturn("call-1");
        CascadeChannelDTO channel = new CascadeChannelDTO();
        channel.setCascadeChannelId(CHANNEL_ID);
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(PLATFORM_ID, CHANNEL_ID)).thenReturn(channel);

        assertTrue(handler.handle(platform(), notify(CHANNEL_ID)));

        assertTrue(captureXml().contains("<Result>OK</Result>"));
    }

    @Test
    @DisplayName("目标不认识：回 ERROR（不发沉默）")
    void unknownTarget_returnsError() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), isNull(), isNull())).thenReturn("call-1");
        when(cascadeChannelManager.getByPlatformAndCascadeChannelId(eq(PLATFORM_ID), anyString())).thenReturn(null);

        assertTrue(handler.handle(platform(), notify("34020000001320000099")));

        String xml = captureXml();
        assertTrue(xml.contains("<Result>ERROR</Result>"), xml);
        assertTrue(xml.contains("<DeviceID>34020000001320000099</DeviceID>"), "目标编码要原样带回: " + xml);
    }

    @Test
    @DisplayName("TargetID 缺失：回 ERROR")
    void blankTarget_returnsError() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), isNull(), isNull())).thenReturn("call-1");

        assertTrue(handler.handle(platform(), notify(" ")));

        assertTrue(captureXml().contains("<Result>ERROR</Result>"));
    }

    @Test
    @DisplayName("平台地址不可用时返回 false，不抛异常")
    void sendFailure_returnsFalse() {
        when(upperMessenger.send(any(CascadePlatformDTO.class), anyString(), isNull(), isNull())).thenReturn(null);

        assertFalse(handler.handle(platform(), notify(LOCAL_ID)));
    }

    @Test
    @DisplayName("入参为空：安静返回 false")
    void nullInput_returnsFalse() {
        assertFalse(handler.handle(null, notify(LOCAL_ID)));
        assertFalse(handler.handle(platform(), null));
        verifyNoInteractions(upperMessenger);
    }
}
