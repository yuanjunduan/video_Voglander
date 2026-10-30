package io.github.lunasaw.voglander.manager.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import javax.sip.message.Request;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.sip.common.transmit.SipSender;
import io.github.lunasaw.sip.common.transmit.event.Event;
import io.github.lunasaw.sip.common.transmit.request.SipRequestBuilderFactory;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeDeviceSupplier;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade.CascadeUpperMessenger;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;

/**
 * {@link CascadeUpperMessenger} 单元测试。
 *
 * <p>本类守的是<b>身份</b>这条线：From 必须是 {@code tb_cascade_platform.local_client_id}（平台编码），
 * 不能是设备侧客户端编码；Call-ID 没指定时才新建；平台地址不全时安静返回 {@code null}，
 * 让调用方回退框架默认实现而不是抛异常打断整条消息链。
 *
 * @author luna
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeUpperMessenger 单元测试")
class CascadeUpperMessengerTest {

    private static final String PLATFORM_ID   = "37000020002000000100";
    private static final String LOCAL_ID      = "34020000002000000001";
    private static final String LOCAL_IP      = "192.168.118.7";
    private static final String PLATFORM_IP   = "192.168.118.200";
    private static final String XML           = "<Response><CmdType>DeviceInfo</CmdType></Response>";

    @Mock CascadePlatformManager cascadePlatformManager;
    @Mock Request                request;

    private CascadeUpperMessenger messenger;

    @BeforeEach
    void setUp() {
        /* 用真实 Supplier：From 的 userId 就是把 localClientId 原样搬过去，这正是本类要守的契约 */
        messenger = new CascadeUpperMessenger(cascadePlatformManager, new CascadeDeviceSupplier());
    }

    private CascadePlatformDTO platform() {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(PLATFORM_ID);
        platform.setPlatformIp(PLATFORM_IP);
        platform.setPlatformPort(5060);
        platform.setLocalClientId(LOCAL_ID);
        platform.setLocalIp(LOCAL_IP);
        platform.setLocalPort(5061);
        platform.setCharset("GB2312");
        return platform;
    }

    @Test
    @DisplayName("From 用平台编码、To 用上级编码，Call-ID 原样沿用")
    void send_usesPlatformIdentity() {
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);

            String used = messenger.send(platform(), XML, "in-call", null);

            assertEquals("in-call", used);
            ArgumentCaptor<FromDevice> from = ArgumentCaptor.forClass(FromDevice.class);
            ArgumentCaptor<ToDevice> to = ArgumentCaptor.forClass(ToDevice.class);
            builder.verify(() -> SipRequestBuilderFactory.createMessageRequest(from.capture(), to.capture(),
                eq(XML), eq("in-call")));
            assertEquals(LOCAL_ID, from.getValue().getUserId(), "From 必须是平台编码，上级只认这个");
            assertEquals(PLATFORM_ID, to.getValue().getUserId());
            assertEquals(PLATFORM_IP, to.getValue().getIp());
            sender.verify(() -> SipSender.transmitRequest(eq(LOCAL_IP), eq(request), isNull(), isNull()));
        }
    }

    @Test
    @DisplayName("未指定 Call-ID：新建一个并回传")
    void send_generatesCallId() {
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class);
            MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);
            utils.when(SipRequestUtils::getNewCallId).thenReturn("gen-call");

            assertEquals("gen-call", messenger.send(platform(), XML, null, null));
            sender.verify(() -> SipSender.transmitRequest(eq(LOCAL_IP), eq(request), isNull(), isNull()));
        }
    }

    @Test
    @DisplayName("指定了 Call-ID 就不该再生成新的")
    void send_doesNotGenerateWhenCallIdGiven() {
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class);
            MockedStatic<SipRequestUtils> utils = mockStatic(SipRequestUtils.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);

            messenger.send(platform(), XML, "in-call", null);

            utils.verify(SipRequestUtils::getNewCallId, never());
        }
    }

    @Test
    @DisplayName("200 OK 回调原样透传给框架")
    void send_passesOkEvent() {
        Event okEvent = result -> {
        };
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);

            messenger.send(platform(), XML, "in-call", okEvent);

            sender.verify(() -> SipSender.transmitRequest(eq(LOCAL_IP), eq(request), isNull(), eq(okEvent)));
        }
    }

    @Test
    @DisplayName("localIp 未回填：返回 null，不尝试发送")
    void send_missingLocalIp_returnsNull() {
        CascadePlatformDTO platform = platform();
        platform.setLocalIp(" ");

        assertNull(messenger.send(platform, XML, "in-call", null));
    }

    @Test
    @DisplayName("平台入参为空或报文为空：安静返回 null")
    void send_blankInput_returnsNull() {
        assertNull(messenger.send((CascadePlatformDTO)null, XML, "in-call", null));
        assertNull(messenger.send(platform(), "  ", "in-call", null));
    }

    @Test
    @DisplayName("发送异常被吞掉，返回 null 让调用方回退")
    void send_transmitFailure_returnsNull() {
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);
            sender.when(() -> SipSender.transmitRequest(anyString(), any(), any(), any()))
                .thenThrow(new IllegalStateException("socket closed"));

            assertNull(messenger.send(platform(), XML, "in-call", null));
        }
    }

    @Test
    @DisplayName("按平台编码发送：查不到平台时返回 null")
    void sendByPlatformId_unknownPlatform_returnsNull() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(null);

        assertNull(messenger.sendByPlatformId(PLATFORM_ID, XML, "in-call", null));
    }

    @Test
    @DisplayName("按平台编码发送：查到平台则按该平台身份发出")
    void sendByPlatformId_resolvesPlatform() {
        when(cascadePlatformManager.getByPlatformId(PLATFORM_ID)).thenReturn(platform());
        try (MockedStatic<SipRequestBuilderFactory> builder = mockStatic(SipRequestBuilderFactory.class);
            MockedStatic<SipSender> sender = mockStatic(SipSender.class)) {
            builder.when(() -> SipRequestBuilderFactory.createMessageRequest(any(), any(), anyString(), anyString()))
                .thenReturn(request);

            assertEquals("in-call", messenger.sendByPlatformId(PLATFORM_ID, XML, "in-call", null));
            sender.verify(() -> SipSender.transmitRequest(eq(LOCAL_IP), eq(request), isNull(), isNull()));
        }
    }
}
