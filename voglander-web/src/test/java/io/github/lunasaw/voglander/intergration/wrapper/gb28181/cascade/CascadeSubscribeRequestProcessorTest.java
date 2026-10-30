package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.header.EventHeader;
import javax.sip.header.ExpiresHeader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import gov.nist.javax.sip.message.SIPRequest;
import io.github.lunasaw.gb28181.common.entity.query.DeviceQuery;
import io.github.lunasaw.sip.common.utils.SipUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;

/**
 * {@link CascadeSubscribeRequestProcessor} 单元测试。
 *
 * <p>核心回归点：上级 SUBSCRIBE 必须被<b>应答</b>（200 OK + Expires）并登记订阅意图，
 * 而不是被框架的 clientId 方向校验静默丢弃。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeSubscribeRequestProcessor 单元测试")
class CascadeSubscribeRequestProcessorTest {

    private static final String UPPER_PLATFORM_ID = "37000020002000000100";
    private static final String CALL_ID           = "call-11436";

    @Mock
    CascadePlatformManager                       cascadePlatformManager;
    @Mock
    ObjectProvider<CascadeSubscribeHandler>      subscribeHandlerProvider;
    @Mock
    ObjectProvider<CascadeNotifyPublisher>       notifyPublisherProvider;
    @Mock
    CascadeSubscribeHandler                      subscribeHandler;
    @Mock
    CascadeNotifyPublisher                       notifyPublisher;
    @Mock
    RequestEvent                                 requestEvent;
    @Mock
    SIPRequest                                   request;
    @Mock
    EventHeader                                  eventHeader;
    @Mock
    ExpiresHeader                                expiresHeader;
    @Mock
    ServerTransaction                            serverTransaction;

    CascadeSubscribeRequestProcessor             processor;

    @BeforeEach
    void setUp() {
        processor = spy(new CascadeSubscribeRequestProcessor(cascadePlatformManager,
            subscribeHandlerProvider, notifyPublisherProvider));
        /* 真实回包依赖运行时 SipProvider，单测只关心「有没有答、答了多久」 */
        doNothing().when(processor).responseOk(any(), any());
        when(requestEvent.getRequest()).thenReturn(request);
    }

    @Test
    @DisplayName("上级 Catalog 订阅：回 200 OK、登记订阅意图，并立即推一次目录快照")
    void upperPlatformCatalogSubscribe() {
        byte[] body = catalogBody();
        DeviceQuery query = mock(DeviceQuery.class);
        stubCatalogRequest(body);
        when(notifyPublisherProvider.getIfAvailable()).thenReturn(notifyPublisher);
        when(query.getSn()).thenReturn("11436");

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);
            sipUtils.when(() -> SipUtils.getObj("GB2312", DeviceQuery.class, body)).thenReturn(query);

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor).responseOk(requestEvent, 1800);
        verify(subscribeHandler).onCatalogSubscribe(eq(UPPER_PLATFORM_ID), eq(1800), same(query));
        /* 200 OK 不带通道，上级靠这条 NOTIFY 建目录基线，漏了目录永远是空的 */
        verify(notifyPublisher).pushCatalogSnapshot(UPPER_PLATFORM_ID, "11436");
    }

    @Test
    @DisplayName("上级退订（Expires=0）：回 200 OK、登记退订，但不推目录快照")
    void unsubscribeDoesNotPushSnapshot() {
        byte[] body = catalogBody();
        DeviceQuery query = mock(DeviceQuery.class);
        stubCatalogRequest(body);
        when(expiresHeader.getExpires()).thenReturn(0);
        when(notifyPublisherProvider.getIfAvailable()).thenReturn(notifyPublisher);

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);
            sipUtils.when(() -> SipUtils.getObj("GB2312", DeviceQuery.class, body)).thenReturn(query);

            processor.process(requestEvent, serverTransaction);
        }

        verify(subscribeHandler).onCatalogSubscribe(eq(UPPER_PLATFORM_ID), eq(0), same(query));
        verify(notifyPublisher, never()).pushCatalogSnapshot(any(), any());
    }

    @Test
    @DisplayName("非上级平台的 SUBSCRIBE：完全跳过，交给设备侧处理器")
    void ignoreNonUpperPlatform() {
        when(cascadePlatformManager.getByPlatformId(anyString())).thenReturn(null);
        when(request.getHeader(anyString())).thenReturn(null);

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, "34020000001320000001");

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor, never()).responseOk(any(), any());
        verifyNoInteractions(subscribeHandler);
    }

    @Test
    @DisplayName("订阅体缺失：仍然回 200 OK（消掉上级重传），只是不登记")
    void respondEvenWhenBodyMissing() {
        stubCatalogRequest(null);

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor).responseOk(requestEvent, 1800);
        verifyNoInteractions(subscribeHandler);
    }

    @Test
    @DisplayName("lab 模式未装配订阅接收器：仍然回 200 OK")
    void respondEvenWhenHandlerAbsent() {
        when(subscribeHandlerProvider.getIfAvailable()).thenReturn(null);
        stubCatalogRequest(catalogBody());

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor).responseOk(requestEvent, 1800);
    }

    @Test
    @DisplayName("没有 Expires 头时回落到 Event 头的 expires 参数")
    void expiresFromEventParameter() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        when(request.getHeader(ExpiresHeader.NAME)).thenReturn(null);
        when(request.getHeader(EventHeader.NAME)).thenReturn(eventHeader);
        when(eventHeader.getEventType()).thenReturn("Catalog");
        when(eventHeader.toString()).thenReturn("Catalog;expires=900");
        when(request.getRawContent()).thenReturn(null);

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor).responseOk(requestEvent, 900);
    }

    // ============================ helpers ============================

    /** 海康实测报文（简化）：Event: Catalog;id=11436 + Expires: 1800 + Query 体 */
    private void stubCatalogRequest(byte[] body) {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        when(subscribeHandlerProvider.getIfAvailable()).thenReturn(subscribeHandler);
        when(request.getHeader(EventHeader.NAME)).thenReturn(eventHeader);
        when(request.getHeader(ExpiresHeader.NAME)).thenReturn(expiresHeader);
        when(eventHeader.getEventType()).thenReturn("Catalog");
        when(expiresHeader.getExpires()).thenReturn(1800);
        when(request.getRawContent()).thenReturn(body);
    }

    private void stubSipUtils(MockedStatic<SipUtils> sipUtils, String fromUserId) {
        sipUtils.when(() -> SipUtils.getUserIdFromFromHeader(request)).thenReturn(fromUserId);
        sipUtils.when(() -> SipUtils.getCallId(request)).thenReturn(CALL_ID);
    }

    private byte[] catalogBody() {
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?>"
            + "<Query><CmdType>Catalog</CmdType><SN>11436</SN>"
            + "<DeviceID>34020000002000000001</DeviceID></Query>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private CascadePlatformDTO platform() {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(UPPER_PLATFORM_ID);
        platform.setLocalClientId("34020000002000000001");
        platform.setCharset("GB2312");
        return platform;
    }
}
