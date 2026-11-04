package io.github.lunasaw.voglander.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联撤销通道的测试桩：默认「上级已确认」，可按用例切换成拒绝 / 无响应。
 *
 * <p><b>为什么必须桩掉而不是走真实实现</b>：{@code BaseTest} 里 {@code sip.client.enabled=true}，
 * 真实的 {@code CascadeCatalogRevokeGateway} 会被创建。删除入口现在会同步等上级对 {@code Event=DEL}
 * 回 2xx——测试环境里没有真的上级平台应答，每个删除用例都会等满超时再抛「上级未响应」，
 * 全仓已绿的删除用例会集体变红。桩掉之后默认放行，业务语义（先撤销、确认后才删本地）仍然被完整执行。
 *
 * <p><b>为什么用可变桩而不是 Mockito mock</b>：本桩要在多个删除入口之间共享、按类型注入
 * （真实 gateway 仍在容器里，靠 {@code @Primary} 竞争胜出），并且要记录调用序列供「撤销先于本地删除」
 * 的次序断言使用。mock 的 stubbing 在 Spring 单 bean 场景下反而更难表达「这个用例拒绝、下个用例放行」。
 *
 * @author luna
 */
@Slf4j
public class StubCascadeCatalogRevokeService implements CascadeCatalogRevokeService {

    /** 一次撤销调用的入参快照 */
    public record Invocation(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {

        public int nodeCount() {
            return nodes.size();
        }
    }

    /** 本次调用要返回的结论；默认放行，用例可改成 REFUSED / TIMEOUT */
    private volatile Reason                              nextReason    = Reason.CONFIRMED;

    /** 本次调用要返回的 SIP 状态码，仅用于拼装失败原因 */
    private volatile int                                 nextStatusCode = 200;

    /** 结论之外的附加行为：在「撤销时刻」回调，用例用它断言此刻本地数据仍未删 */
    private volatile Consumer<Invocation>                onRevoke;

    private final List<Invocation>                       invocations  = Collections.synchronizedList(new ArrayList<>());

    @Override
    public RevokeResult revoke(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        List<LocalCascadeCatalogChangeEvent.Node> snapshot = nodes == null
            ? List.of()
            : List.copyOf(nodes);
        Invocation invocation = new Invocation(platformId, snapshot);
        invocations.add(invocation);
        Consumer<Invocation> hook = onRevoke;
        if (hook != null) {
            hook.accept(invocation);
        }
        log.info("测试桩收到级联撤销请求: platformId={}, 节点数={}, 结论={}",
            platformId, snapshot.size(), nextReason);
        return new RevokeResult(platformId, nextReason, nextStatusCode, snapshot.size());
    }

    /** 恢复默认：上级确认、无附加回调。用例结束时必须调用，否则会串到其它用例。 */
    public void reset() {
        nextReason = Reason.CONFIRMED;
        nextStatusCode = 200;
        onRevoke = null;
        invocations.clear();
    }

    /** 上级回了非 2xx：删除入口应抛异常且本地一条不删 */
    public void refuse(int statusCode) {
        nextReason = Reason.REFUSED;
        nextStatusCode = statusCode;
    }

    /** 等待窗口内上级毫无响应：等价于超时 */
    public void noResponse() {
        nextReason = Reason.TIMEOUT;
        nextStatusCode = 0;
    }

    /** 报文发不出去 */
    public void sendFailed() {
        nextReason = Reason.SEND_FAILED;
        nextStatusCode = 0;
    }

    /** 挂一个「撤销时刻」回调，用于断言撤销发生在本地删除之前 */
    public void setOnRevoke(Consumer<Invocation> hook) {
        onRevoke = hook;
    }

    public List<Invocation> getInvocations() {
        synchronized (invocations) {
            return new ArrayList<>(invocations);
        }
    }

    public int invocationCount() {
        return invocations.size();
    }

    public void clearInvocations() {
        invocations.clear();
    }
}
