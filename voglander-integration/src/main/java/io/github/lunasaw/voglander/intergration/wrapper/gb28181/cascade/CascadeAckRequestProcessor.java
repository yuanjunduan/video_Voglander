package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import javax.sip.Dialog;
import javax.sip.RequestEvent;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Request;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.github.lunasaw.gbproxy.client.eventbus.event.ClientAckEvent;
import io.github.lunasaw.sip.common.transmit.event.request.SipRequestProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ACK 请求处理器：把"上级确认了 INVITE 对话"翻译成框架的 {@link ClientAckEvent}。
 *
 * <p><b>为什么需要本类</b>：框架里唯一发布 {@link ClientAckEvent} 的是
 * {@code ClientAckResponseProcessor}，它挂在 <em>响应</em> 分派表上（按 CSeq method=ACK 匹配），
 * 而 ACK 在 SIP 里是请求、不是响应，因此该事件在正常流程中永远不会被发布。
 * 直接监听 {@link ClientAckEvent} 的结果是"永远收不到"，点播会因为等不到 ACK 而不推流。
 *
 * <p>本类注册到框架的 <em>请求</em> 分派表（method=ACK），取 Call-ID 后自行发布同一事件，
 * 于是 {@link CascadeMediaInviteListener#onAck(ClientAckEvent)} 等既有监听逻辑无需改动即可生效。
 *
 * <p>注意：本应用的 5060（设备侧）与 5061（级联侧）共用同一套静态处理器表，设备侧的 ACK 也会
 * 经过这里；下游按 callId 查自己的会话表，查不到即忽略，不会互相干扰。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeAckRequestProcessor implements SipRequestProcessor {

    /** 与框架一致的请求 method 分派键 */
    public static final String METHOD_ACK = "ACK";

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void process(RequestEvent evt) {
        String callId = resolveCallId(evt);
        if (callId == null) {
            log.debug("收到无 Call-ID 的 ACK，忽略");
            return;
        }
        Dialog dialog = evt.getDialog();
        /* 2xx 的 ACK 会建立/确认对话；拿不到对话说明是异常 ACK，仍然转发，由下游按 callId 判断 */
        if (dialog == null) {
            log.warn("ACK 没有关联对话（可能是事务超时后的迟到 ACK）: callId={}", callId);
        }
        eventPublisher.publishEvent(new ClientAckEvent(this, callId));
        log.debug("级联 ACK 已转发为内部事件: callId={}", callId);
    }

    private String resolveCallId(RequestEvent evt) {
        Request request = evt.getRequest();
        if (request == null) {
            return null;
        }
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        return callIdHeader != null ? callIdHeader.getCallId() : null;
    }
}
