package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.lunasaw.sip.common.transmit.AbstractSipListener;
import io.github.lunasaw.sip.common.transmit.CustomerSipListener;
import io.github.lunasaw.sip.common.transmit.event.request.SipRequestProcessor;
import io.github.lunasaw.sip.common.transmit.event.response.SipResponseProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 把 SIP 栈里默认的 REGISTER 响应处理器换成 {@link CascadeRegisterAuthProcessor}。
 *
 * <p>框架的 {@code SipProxyAutoConfig} 在容器启动早期把所有 {@link SipResponseProcessor} bean
 * 按 method 注册进 {@link AbstractSipListener} 的静态表，同一 method 允许多个处理器顺序执行。
 * 默认处理器会用全局客户端身份做 401 重认证（对级联上级必然 403），因此这里在
 * {@link ApplicationReadyEvent} 时把它摘掉、只保留级联处理器。
 *
 * <p>顺序必须早于 {@code CascadeClientScheduler#onApplicationReady}：后者以 initialDelay=0
 * 立刻发出首个 REGISTER，若此时处理器还没换过来，第一条 401 就会走错分支。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeRegisterProcessorInstaller {

    private static final String METHOD_REGISTER  = "REGISTER";
    private static final String METHOD_MESSAGE   = "MESSAGE";
    private static final String METHOD_ACK       = CascadeAckRequestProcessor.METHOD_ACK;
    private static final String METHOD_SUBSCRIBE = CascadeSubscribeRequestProcessor.METHOD_SUBSCRIBE;

    private final ApplicationContext applicationContext;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void install() {
        AbstractSipListener listener = CustomerSipListener.getInstance();
        if (listener == null) {
            log.warn("SIP 监听器尚未初始化，跳过级联注册处理器装配");
            return;
        }

        Map<String, SipResponseProcessor> beans = applicationContext.getBeansOfType(SipResponseProcessor.class);

        installMessageAliveProcessor(listener, beans);

        installAckProcessor(listener);

        /* 上级 MESSAGE / SUBSCRIBE 必须摘掉框架默认处理器：它按「本端 clientId == To」做方向校验，
           而上级的 To 是本端平台域，天然不等，请求会被静默丢弃且不回 200 OK */
        takeoverRequestProcessor(listener, METHOD_MESSAGE, CascadeMessageRequestProcessor.class);
        takeoverRequestProcessor(listener, METHOD_SUBSCRIBE, CascadeSubscribeRequestProcessor.class);

        List<SipResponseProcessor>        registerProcessors = new ArrayList<>();
        CascadeRegisterAuthProcessor      cascadeProcessor    = null;
        for (SipResponseProcessor processor : beans.values()) {
            if (!METHOD_REGISTER.equalsIgnoreCase(processor.getMethod())) {
                continue;
            }
            registerProcessors.add(processor);
            if (processor instanceof CascadeRegisterAuthProcessor authProcessor) {
                cascadeProcessor = authProcessor;
            }
        }

        if (cascadeProcessor == null) {
            log.warn("未找到 CascadeRegisterAuthProcessor bean，级联 401 重认证仍走框架默认身份");
            return;
        }

        for (SipResponseProcessor processor : registerProcessors) {
            listener.removeResponseProcessor(METHOD_REGISTER, processor);
        }
        listener.addResponseProcessor(METHOD_REGISTER, cascadeProcessor);
        log.info("级联注册响应处理器已接管: 摘除默认处理器 {} 个，当前注册处理器={}",
            registerProcessors.size() - 1, cascadeProcessor.getClass().getSimpleName());
    }

    /**
     * 注册 MESSAGE 响应监听（上级存活信号）。与 REGISTER 不同，这里**不做替换**：
     * 框架自带的 MESSAGE 处理器仍照常工作，本监听只是旁路登记。
     *
     * <p>框架的 {@code SipProxyAutoConfig} 通常已经按 method 自动注册过该 bean，
     * 因此这里先查表再补，避免同一实例被登记两次（同一响应会被处理两遍）。
     */
    private void installMessageAliveProcessor(AbstractSipListener listener,
        Map<String, SipResponseProcessor> beans) {
        CascadeMessageResponseProcessor aliveProcessor = null;
        for (SipResponseProcessor processor : beans.values()) {
            if (processor instanceof CascadeMessageResponseProcessor messageProcessor) {
                aliveProcessor = messageProcessor;
                break;
            }
        }
        if (aliveProcessor == null) {
            log.warn("未找到 CascadeMessageResponseProcessor bean，级联心跳超时判定将缺少存活信号");
            return;
        }

        List<SipResponseProcessor> current = listener.getResponseProcessors(METHOD_MESSAGE);
        if (current != null && current.contains(aliveProcessor)) {
            log.info("级联 MESSAGE 响应监听已存在: 当前 MESSAGE 处理器 {} 个", current.size());
            return;
        }
        listener.addResponseProcessor(METHOD_MESSAGE, aliveProcessor);
        List<SipResponseProcessor> after = listener.getResponseProcessors(METHOD_MESSAGE);
        log.info("级联 MESSAGE 响应监听已注册: 当前 MESSAGE 处理器 {} 个",
            after == null ? 1 : after.size());
    }

    /**
     * 注册 ACK <b>请求</b> 监听：把上级对 INVITE 的确认翻译成 {@link io.github.lunasaw.gbproxy.client.eventbus.event.ClientAckEvent}。
     *
     * <p>框架自带的 {@code ClientAckResponseProcessor} 挂在响应分派表上，而 ACK 是请求，
     * 因此 {@code ClientAckEvent} 在正常流程中永远不会被发布；不补这一步，
     * {@link CascadeMediaInviteListener} 的"ACK 后推流"就永远等不到事件。
     */
    private void installAckProcessor(AbstractSipListener listener) {
        CascadeAckRequestProcessor ackProcessor =
            applicationContext.getBeanProvider(CascadeAckRequestProcessor.class).getIfAvailable();
        if (ackProcessor == null) {
            log.warn("未找到 CascadeAckRequestProcessor bean，级联点播将缺少 ACK 触发信号");
            return;
        }
        List<SipRequestProcessor> current = listener.getRequestProcessors(METHOD_ACK);
        if (current != null && current.contains(ackProcessor)) {
            log.info("级联 ACK 请求监听已存在: 当前 ACK 处理器 {} 个", current.size());
            return;
        }
        listener.addRequestProcessor(METHOD_ACK, ackProcessor);
        List<SipRequestProcessor> after = listener.getRequestProcessors(METHOD_ACK);
        log.info("级联 ACK 请求监听已注册: 当前 ACK 处理器 {} 个", after == null ? 1 : after.size());
    }

    /**
     * 用指定处理器<b>整体接管</b>某个 method 的请求分派。
     *
     * <p>框架的 {@code ClientMessageRequestProcessor} / {@code SubscribeRequestProcessor} 都以
     * 「本端 clientId == To 头」判断请求是否发给自己。本平台作为<b>下级</b>被上级查询时，To 是本端平台域
     * （{@code tb_cascade_platform.local_client_id}），与 {@code sip.client.clientId} 天然不等，
     * 请求会被 warn 后静默丢弃、连 200 OK 都不回，上级只能重传三次后放弃。
     * {@link CascadeMessageRequestProcessor} / {@link CascadeSubscribeRequestProcessor} 改用
     * 「from 是否为已知上级平台」判定，因此这里要把框架默认处理器摘掉：既避免它先跑一遍打噪音 warn，
     * 也保证每个请求只被处理一次。
     *
     * @param method 请求 method（MESSAGE / SUBSCRIBE）
     * @param type   接管者的类型
     */
    private <T extends SipRequestProcessor> void takeoverRequestProcessor(AbstractSipListener listener,
        String method, Class<T> type) {
        T takeover = applicationContext.getBeanProvider(type).getIfAvailable();
        if (takeover == null) {
            log.warn("未找到 {} bean，{} 请求仍走框架默认处理器（上级查询将被丢弃）",
                type.getSimpleName(), method);
            return;
        }

        List<SipRequestProcessor> current = listener.getRequestProcessors(method);
        int removed = 0;
        if (current != null) {
            /* getRequestProcessors 返回的是内部列表本体，遍历中删除需先拷贝快照 */
            for (SipRequestProcessor processor : new ArrayList<>(current)) {
                if (processor != takeover) {
                    listener.removeRequestProcessor(method, processor);
                    removed++;
                }
            }
        }

        List<SipRequestProcessor> after = listener.getRequestProcessors(method);
        if (after == null || !after.contains(takeover)) {
            listener.addRequestProcessor(method, takeover);
        }
        log.info("级联 {} 请求处理器已接管: 摘除框架默认处理器 {} 个，当前处理器={}",
            method, removed, takeover.getClass().getSimpleName());
    }
}