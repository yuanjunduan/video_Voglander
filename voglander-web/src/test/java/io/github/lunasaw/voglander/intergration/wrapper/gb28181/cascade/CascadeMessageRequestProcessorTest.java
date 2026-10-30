package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.message.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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

import gov.nist.javax.sip.message.SIPRequest;
import io.github.lunasaw.gb28181.common.entity.notify.DeviceBroadcastNotify;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.transmit.ResponseCmd;
import io.github.lunasaw.sip.common.transmit.event.message.MessageHandler;
import io.github.lunasaw.sip.common.transmit.event.request.SipRequestProcessorAbstract;
import io.github.lunasaw.sip.common.utils.SipUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;

/**
 * {@link CascadeMessageRequestProcessor} 单元测试。
 *
 * <p>核心回归点：上级 MESSAGE 不能被 {@code to == clientId} 的方向校验挡住；转交消息链时
 * 必须给框架一个<b>非 null</b> 的 {@code FromDevice}（框架内部 {@code Optional.of(fromDevice)} 会 NPE）；
 * 找不到处理器时必须自己回 200 OK（否则上级按 Timer E/F 一直重传）；语音广播在 200 OK 之后还要补
 * {@code Response/Broadcast}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CascadeMessageRequestProcessor 单元测试")
class CascadeMessageRequestProcessorTest {

    private static final String UPPER_PLATFORM_ID = "37000020002000000100";
    private static final String LOCAL_CLIENT_ID   = "34020000002000000001";

    private static final String CATALOG_XML       = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n"
        + "<Query>\n <CmdType>Catalog</CmdType>\n <SN>1</SN>\n <DeviceID>" + LOCAL_CLIENT_ID + "</DeviceID>\n</Query>\n";

    private static final String BROADCAST_XML     = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\n"
        + "<Notify>\n <CmdType>Broadcast</CmdType>\n <SN>992</SN>\n"
        + " <SourceID>37000000001360000001</SourceID>\n <TargetID>" + LOCAL_CLIENT_ID + "</TargetID>\n</Notify>\n";

    @Mock
    CascadePlatformManager              cascadePlatformManager;
    @Mock
    CascadeBroadcastHandler             cascadeBroadcastHandler;
    @Mock
    RequestEvent                        requestEvent;
    @Mock
    SIPRequest                          request;
    @Mock
    ServerTransaction                   serverTransaction;

    CascadeMessageRequestProcessor      processor;

    /** 登记过的分派键的还原动作：这张表是 JVM 级静态表，不还原会串到同一 fork 里的 e2e 测试 */
    private final List<Runnable> restores = new ArrayList<>();

    @BeforeEach
    void setUp() {
        processor = spy(new CascadeMessageRequestProcessor(cascadePlatformManager, cascadeBroadcastHandler));
        /* 真实的消息链依赖 SIP 运行时，这里只验「是否转交、转交时给了什么」 */
        doNothing().when(processor).doMessageHandForEvt(any(), any(), any());
        when(requestEvent.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn(CascadeMessageRequestProcessor.METHOD_MESSAGE);
        when(request.getRawContent()).thenReturn(CATALOG_XML.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        restores.forEach(Runnable::run);
        restores.clear();
    }

    @Test
    @DisplayName("上级 Catalog 查询：转交消息链，且 FromDevice 非 null 并带平台字符集")
    void upperPlatformMessageDispatched() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        registerHandler(MessageHandler.QUERY, "Catalog");

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        ArgumentCaptor<FromDevice> fromCaptor = ArgumentCaptor.forClass(FromDevice.class);
        verify(processor).doMessageHandForEvt(eq(requestEvent), fromCaptor.capture(), eq(serverTransaction));
        FromDevice from = fromCaptor.getValue();
        Assertions.assertNotNull(from, "框架内部 Optional.of(fromDevice) 对 null 会直接抛 NPE");
        Assertions.assertEquals(UPPER_PLATFORM_ID, from.getUserId());
        Assertions.assertEquals("GB2312", from.getCharset());
    }

    @Test
    @DisplayName("未见过的 CmdType：不转交，但必须回 200 OK 收尾")
    void unknownCmdType_returnsOk() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        when(request.getRawContent()).thenReturn(
            "<Query>\n <CmdType>NotHandledByUs</CmdType>\n <SN>1</SN>\n</Query>".getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class);
            MockedStatic<ResponseCmd> responseCmd = mockStatic(ResponseCmd.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);

            responseCmd.verify(() -> ResponseCmd.sendResponse(eq(Response.OK), eq(requestEvent),
                eq(serverTransaction)));
        }

        verify(processor, never()).doMessageHandForEvt(any(), any(), any());
    }

    @Test
    @DisplayName("语音广播：转交消息链后补一条 Response/Broadcast")
    void broadcast_notifiesHandler() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        when(request.getRawContent()).thenReturn(BROADCAST_XML.getBytes(StandardCharsets.UTF_8));
        registerHandler(MessageHandler.NOTIFY, "Broadcast");

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        ArgumentCaptor<CascadePlatformDTO> platformCaptor = ArgumentCaptor.forClass(CascadePlatformDTO.class);
        ArgumentCaptor<DeviceBroadcastNotify> captor = ArgumentCaptor.forClass(DeviceBroadcastNotify.class);
        verify(cascadeBroadcastHandler).handle(platformCaptor.capture(), captor.capture());
        Assertions.assertEquals(UPPER_PLATFORM_ID, platformCaptor.getValue().getPlatformId());
        Assertions.assertEquals(LOCAL_CLIENT_ID, captor.getValue().getTargetId());
        Assertions.assertEquals("992", captor.getValue().getSn());
    }

    @Test
    @DisplayName("非广播 Notify：不触发广播应答")
    void nonBroadcastNotify_noResponse() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID)).thenReturn(platform());
        when(request.getRawContent()).thenReturn(
            "<Notify>\n <CmdType>Keepalive</CmdType>\n <SN>3</SN>\n <DeviceID>x</DeviceID>\n</Notify>"
                .getBytes(StandardCharsets.UTF_8));
        registerHandler(MessageHandler.NOTIFY, "Keepalive");

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            processor.process(requestEvent, serverTransaction);
        }

        verifyNoInteractions(cascadeBroadcastHandler);
    }

    @Test
    @DisplayName("非上级平台的 MESSAGE：跳过，交给设备侧处理器")
    void ignoreNonUpperPlatform() {
        when(cascadePlatformManager.getByPlatformId(anyString())).thenReturn(null);

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, "34020000001320000001");

            processor.process(requestEvent, serverTransaction);
        }

        verify(processor, never()).doMessageHandForEvt(any(), any(), any());
    }

    @Test
    @DisplayName("查平台配置异常：按非级联处理，不抛异常")
    void ignoreWhenLookupFails() {
        when(cascadePlatformManager.getByPlatformId(UPPER_PLATFORM_ID))
            .thenThrow(new IllegalStateException("db down"));

        try (MockedStatic<SipUtils> sipUtils = mockStatic(SipUtils.class)) {
            stubSipUtils(sipUtils, UPPER_PLATFORM_ID);

            Assertions.assertDoesNotThrow(() -> processor.process(requestEvent, serverTransaction));
        }

        verify(processor, never()).doMessageHandForEvt(any(), any(), any());
    }

    // ============================ helpers ============================

    /**
     * 向框架的分派表登记一个处理器：{@code hasHandler} 查的就是这张表，
     * 单测里没有 Spring 容器，必须自己登记才能走到「转交」分支。
     *
     * <p>登记前的旧值（通常为 {@code null}，Spring 环境里可能是真实处理器）会记下来，
     * 由 {@link #tearDown()} 还原，避免污染同一 JVM 里的其它测试。
     */
    private void registerHandler(String rootType, String cmdType) {
        MessageHandler handler = mock(MessageHandler.class);
        when(handler.getRootType()).thenReturn(rootType);
        when(handler.getCmdType()).thenReturn(cmdType);
        when(handler.getMethod()).thenReturn(CascadeMessageRequestProcessor.METHOD_MESSAGE);
        Map<String, MessageHandler> handlers = SipRequestProcessorAbstract.MESSAGE_HANDLER_CMD_MAP.get(rootType);
        boolean innerExisted = handlers != null;
        String key = CascadeMessageRequestProcessor.METHOD_MESSAGE + "_" + cmdType;
        MessageHandler previous = innerExisted ? handlers.get(key) : null;
        SipRequestProcessorAbstract.addHandler(handler);
        restores.add(() -> { restore(rootType, key, previous, innerExisted); });
    }

    private void restore(String rootType, String key, MessageHandler previous, boolean innerExisted) {
        if (!innerExisted) {
            SipRequestProcessorAbstract.MESSAGE_HANDLER_CMD_MAP.remove(rootType);
            return;
        }
        Map<String, MessageHandler> handlers = SipRequestProcessorAbstract.MESSAGE_HANDLER_CMD_MAP.get(rootType);
        if (handlers == null) {
            return;
        }
        if (previous == null) {
            handlers.remove(key);
        } else {
            handlers.put(key, previous);
        }
    }

    private void stubSipUtils(MockedStatic<SipUtils> sipUtils, String fromUserId) {
        sipUtils.when(() -> SipUtils.getUserIdFromFromHeader(request)).thenReturn(fromUserId);
        sipUtils.when(() -> SipUtils.getUserIdFromToHeader(request)).thenReturn(LOCAL_CLIENT_ID);
        sipUtils.when(() -> SipUtils.getCallId(request)).thenReturn("call-1");
    }

    private CascadePlatformDTO platform() {
        CascadePlatformDTO platform = new CascadePlatformDTO();
        platform.setPlatformId(UPPER_PLATFORM_ID);
        platform.setLocalClientId(LOCAL_CLIENT_ID);
        platform.setCharset("GB2312");
        return platform;
    }
}
