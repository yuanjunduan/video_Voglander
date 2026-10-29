package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 上级平台存活信号记录器。
 *
 * <p>GB28181 的心跳（Keepalive MESSAGE）只有请求应答，没有独立的"上级在线/离线"通知。
 * 框架发出的 Keepalive 是否被上级收到，只能通过 SIP 响应反推：本项目在 MESSAGE 与 REGISTER
 * 的响应处理器里登记"最近一次收到该平台响应"的时间，由
 * {@link CascadeClientScheduler} 在心跳周期里判定是否超时。
 *
 * <p>之所以要区分 {@code keepaliveAnswered}：部分上级不回 MESSAGE 的 200（或只在收到查询时才回），
 * 若直接按"多久没响应"判定离线，会对这类平台造成误伤，表现为我方反复重注册。
 * 因此只有观察到过至少一次 MESSAGE 响应后，才启用心跳超时判定。
 *
 * <p>记录全在内存，进程重启即清空——与注册状态一样，重启后由新一轮注册重建基线。
 *
 * @author luna
 */
@Slf4j
@Component
public class CascadePeerAliveTracker {

    /** platformId → 存活状态 */
    private final ConcurrentHashMap<String, PeerState> states = new ConcurrentHashMap<>();

    /**
     * 注册成功：重置基线。此时上级刚回过 200，是最可靠的"活着"证据。
     */
    public void markRegisterSuccess(String platformId) {
        if (platformId == null) {
            return;
        }
        PeerState state = state(platformId);
        long       now   = System.currentTimeMillis();
        state.lastAnyResponseAt = now;
        state.registerSuccessAt = now;
        state.keepaliveAnswered = false;
        state.lastKeepaliveResponseAt = 0L;
        log.debug("上级存活基线重置(注册成功): platformId={}", platformId);
    }

    /**
     * 收到该平台任意 SIP 响应（MESSAGE 的 200/4xx、REGISTER 的 200/401 等）。
     *
     * @param keepalive 是否为我们发出的 MESSAGE（心跳/通知）得到的响应
     */
    public void markResponse(String platformId, boolean keepalive) {
        if (platformId == null) {
            return;
        }
        PeerState state = state(platformId);
        long       now   = System.currentTimeMillis();
        state.lastAnyResponseAt = now;
        if (keepalive) {
            state.lastKeepaliveResponseAt = now;
            state.keepaliveAnswered = true;
        }
    }

    /** 是否观察到过该平台对 MESSAGE 的响应（决定是否启用超时判定）。 */
    public boolean isKeepaliveAnswered(String platformId) {
        PeerState state = platformId == null ? null : states.get(platformId);
        return state != null && state.keepaliveAnswered;
    }

    /**
     * 最近一次"活着"的时间：取任意响应与心跳响应中较新者；从未有过响应时回落到注册成功时间。
     *
     * @return 毫秒时间戳；无任何记录返回 0
     */
    public long lastAliveAt(String platformId) {
        PeerState state = platformId == null ? null : states.get(platformId);
        if (state == null) {
            return 0L;
        }
        return Math.max(Math.max(state.lastAnyResponseAt, state.lastKeepaliveResponseAt),
            state.registerSuccessAt);
    }

    /** 清空某平台的存活记录（判定超时后重注册前调用，避免同一超时被反复触发）。 */
    public void reset(String platformId) {
        if (platformId == null) {
            return;
        }
        states.remove(platformId);
        log.debug("上级存活记录已清空: platformId={}", platformId);
    }

    private PeerState state(String platformId) {
        return states.computeIfAbsent(platformId, k -> new PeerState());
    }

    /** 单平台存活状态；字段用 volatile 保证跨线程可见（写：SIP 响应线程，读：调度线程）。 */
    private static final class PeerState {

        /** 最近一次收到该平台任意 SIP 响应的时间 */
        private volatile long    lastAnyResponseAt;
        /** 最近一次收到该平台 MESSAGE 响应的时间 */
        private volatile long    lastKeepaliveResponseAt;
        /** 是否观察到过 MESSAGE 响应 */
        private volatile boolean keepaliveAnswered;
        /** 最近一次注册成功时间（无任何响应时的兜底基线） */
        private volatile long    registerSuccessAt;
    }
}
