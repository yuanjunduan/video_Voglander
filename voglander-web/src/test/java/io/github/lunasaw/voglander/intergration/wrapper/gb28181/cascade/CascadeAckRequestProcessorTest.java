package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import javax.sip.RequestEvent;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Request;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import io.github.lunasaw.gbproxy.client.eventbus.event.ClientAckEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("CascadeAckRequestProcessor 单元测试")
class CascadeAckRequestProcessorTest {

    @Mock
    ApplicationEventPublisher eventPublisher;

    @SuppressWarnings("unused")
    private CascadeAckRequestProcessor newProcessor() {
        return new CascadeAckRequestProcessor(eventPublisher);
    }

    @Test
    @DisplayName("ACK：提取 Call-ID 并发布 ClientAckEvent")
    void ack_publishes_call_id_event() {
        CascadeAckRequestProcessor processor = newProcessor();
        RequestEvent event = requestEvent("call-ack-1");

        processor.process(event);

        ArgumentCaptor<ClientAckEvent> captor = ArgumentCaptor.forClass(ClientAckEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        Assertions.assertEquals("call-ack-1", captor.getValue().getCallId());
    }

    @Test
    @DisplayName("ACK：缺少 Call-ID 时不发布事件")
    void ack_without_call_id_ignored() {
        CascadeAckRequestProcessor processor = newProcessor();
        RequestEvent event = requestEvent(null);

        Assertions.assertDoesNotThrow(() -> processor.process(event));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ACK：无请求体时不抛异常")
    void ack_without_request_no_exception() {
        CascadeAckRequestProcessor processor = newProcessor();
        RequestEvent event = mock(RequestEvent.class);

        Assertions.assertDoesNotThrow(() -> processor.process(event));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private RequestEvent requestEvent(String callId) {
        Request request = mock(Request.class);
        CallIdHeader header = mock(CallIdHeader.class);
        when(request.getHeader(CallIdHeader.NAME)).thenReturn(header);
        when(header.getCallId()).thenReturn(callId);

        RequestEvent event = mock(RequestEvent.class);
        when(event.getRequest()).thenReturn(request);
        return event;
    }
}
