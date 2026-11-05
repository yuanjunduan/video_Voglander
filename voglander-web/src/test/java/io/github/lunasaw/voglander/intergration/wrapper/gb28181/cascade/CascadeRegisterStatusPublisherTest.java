package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.common.event.SseRelayEvent;

/**
 * SSE 主题 {@code cascade.register} 的唯一发布点。
 *
 * <p>注册结果（listener）与超时/发送异常（scheduler）两个调用方共用本组件，
 * payload 形状必须稳定：前端 {@code useCascadeStatusRefresh} 按
 * {@code topic + data.platformId + data.registerStatus} 消费，去重键还带 {@code data.ts}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CascadeRegisterStatusPublisher — cascade.register 事件形状")
class CascadeRegisterStatusPublisherTest {

    private static final String PLATFORM_ID = "37000020002000000100";

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("发布失败状态 → topic=cascade.register，payload 含 platformId/registerStatus/ts")
    void publish_shouldEmitRegisterEventWithTimestamp() {
        new CascadeRegisterStatusPublisher(eventPublisher)
            .publish(PLATFORM_ID, CascadeConstant.RegisterStatus.FAILED);

        SseRelayEvent event = captureEvent();
        assertEquals("cascade.register", event.getTopic());

        Map<String, Object> data = event.getData();
        assertEquals(PLATFORM_ID, data.get("platformId"));
        assertEquals(CascadeConstant.RegisterStatus.FAILED, data.get("registerStatus"));
        /* ts 参与前端去重键：缺失会让同值状态的后续事件被静默丢弃 */
        assertNotNull(data.get("ts"), "payload 必须带毫秒 ts，否则前端去重键恒定");
        assertTrue(((Number)data.get("ts")).longValue() > 0);
    }

    @Test
    @DisplayName("发布在线状态 → registerStatus=1，与失败状态同一 topic")
    void publish_shouldUseSameTopicForOnline() {
        new CascadeRegisterStatusPublisher(eventPublisher)
            .publish(PLATFORM_ID, CascadeConstant.RegisterStatus.ONLINE);

        SseRelayEvent event = captureEvent();
        assertEquals("cascade.register", event.getTopic());
        assertEquals(CascadeConstant.RegisterStatus.ONLINE, event.getData().get("registerStatus"));
    }

    private SseRelayEvent captureEvent() {
        ArgumentCaptor<SseRelayEvent> captor = ArgumentCaptor.forClass(SseRelayEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
