package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import javax.sip.ResponseEvent;
import javax.sip.address.URI;
import javax.sip.address.SipURI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Response;

import org.springframework.stereotype.Component;

import io.github.lunasaw.sip.common.transmit.event.response.SipResponseProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联 MESSAGE 响应监听：把"上级回了 MESSAGE"登记为存活信号。
 *
 * <p>本类只做旁路记录，不改变任何响应处理流程（同一 method 下允许存在多个
 * {@link SipResponseProcessor}，框架按注册顺序依次执行）。
 *
 * <p>为什么挂在 MESSAGE 上：我方周期性发出的 Keepalive 就是 MESSAGE 请求，其响应按请求
 * method 分派；目录变更/告警等主动 NOTIFY 同样是 MESSAGE，因此只要有 MESSAGE 往返，
 * 就能证明上级信令链路仍然畅通。判定逻辑在 {@link CascadeClientScheduler}。
 *
 * <p>platformId 取自响应 To 头 user 部分——我方 MESSAGE 请求的 To 是上级平台编码，
 * 上级回包时 To 保持不变，据此可定位平台配置。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeMessageResponseProcessor implements SipResponseProcessor {

    /** 与框架一致：响应按请求 method 分派 */
    private static final String METHOD_MESSAGE = "MESSAGE";

    private final CascadePeerAliveTracker  peerAliveTracker;
    private final CascadeNotifyAckTracker  notifyAckTracker;

    @Override
    public String getMethod() {
        return METHOD_MESSAGE;
    }

    @Override
    public void process(ResponseEvent evt) {
        Response response = evt.getResponse();
        if (response == null) {
            return;
        }
        /* 出站推送的送达确认：与存活登记互不依赖，先结算再登记 */
        notifyAckTracker.onResponse(resolveCallId(response), response.getStatusCode());

        String platformId = resolveToUserId(response);
        if (platformId == null) {
            log.debug("收到无法定位上级的 MESSAGE 响应: status={}", response.getStatusCode());
            return;
        }
        /* 任何状态码都算链路存活：4xx 说明上级在线但拒绝了本次请求，比静默更接近"在线" */
        peerAliveTracker.markResponse(platformId, true);
        log.debug("收到上级 MESSAGE 响应: platformId={}, status={}, cseq={}", platformId,
            response.getStatusCode(), resolveCSeq(response));
    }

    /**
     * 从响应 To 头取上级平台编码。
     *
     * <p>本项目的 jain-sip 版本里 {@code Response} 只暴露 getHeader，取 To 必须走头部对象。
     */
    private String resolveToUserId(Response response) {
        ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
        if (toHeader == null || toHeader.getAddress() == null) {
            return null;
        }
        URI uri = toHeader.getAddress().getURI();
        return uri instanceof SipURI sipUri ? sipUri.getUser() : null;
    }

    private Long resolveCSeq(Response response) {
        CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        return cseq != null ? cseq.getSeqNumber() : null;
    }

    /** 取响应的 Call-ID：出站推送以 callId 为送达确认的匹配键。 */
    private String resolveCallId(Response response) {
        CallIdHeader callIdHeader = (CallIdHeader) response.getHeader(CallIdHeader.NAME);
        return callIdHeader != null ? callIdHeader.getCallId() : null;
    }
}
