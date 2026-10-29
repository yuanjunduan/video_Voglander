package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联上级平台注册与心跳调度器。
 *
 * <p>每个 enabled 平台拥有两套独立的 {@link ScheduledFuture}（C1）：
 * <ul>
 *   <li><b>注册续期</b>：按 {@code registerExpires} 的 2/3 周期重发 REGISTER，保持注册有效；</li>
 *   <li><b>保活心跳</b>：独立的 Keepalive MESSAGE 心跳（默认 60s），仅在 ONLINE 时发送。</li>
 * </ul>
 * 注册续期与保活心跳是 GB28181 §9.1 的两个独立机制。单个平台失败不影响其他平台。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeClientScheduler {

    private final CascadePlatformManager  cascadePlatformManager;
    private final CascadeDeviceSupplier   cascadeDeviceSupplier;
    private final CascadePeerAliveTracker peerAliveTracker;
    private final CascadeUnregisterService cascadeUnregisterService;

    /** REGISTER 发出后等待成功/失败事件的最长时间，超时仍为 REGISTERING 则转 FAILED。 */
    @Value("${gateway.cascade.register-timeout-sec:" + CascadeConstant.DEFAULT_REGISTER_TIMEOUT_SEC + "}")
    private long                         registerTimeoutSeconds = CascadeConstant.DEFAULT_REGISTER_TIMEOUT_SEC;

    /**
     * 心跳超时判定倍数：连续 N 个心跳周期收不到上级任何响应即判定失联并自愈重注册。
     * 仅在曾观察到该平台 MESSAGE 响应后生效，避免误伤不回 200 的上级。
     */
    @Value("${gateway.cascade.keepalive-timeout-count:3}")
    private int                          keepaliveTimeoutCount   = 3;

    /** 两次 REGISTER 之间的最小间隔（毫秒）：周期续期、退避重试、心跳自愈三条路径可能同时触发。 */
    private static final long MIN_REGISTER_INTERVAL_MS = 10_000L;

    /** platformId → 注册续期任务 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> registerTasks  = new ConcurrentHashMap<>();
    /** platformId → 保活心跳任务 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> keepaliveTasks = new ConcurrentHashMap<>();
    /** platformId → 连续注册失败次数（用于失败后的快速退避重试） */
    private final ConcurrentHashMap<String, Integer>            retryCounts    = new ConcurrentHashMap<>();
    /** platformId → 最近一次 REGISTER 发送时间（毫秒），用于抑制并发重复注册 */
    private final ConcurrentHashMap<String, Long>               lastRegisterAt = new ConcurrentHashMap<>();

    /**
     * 失败退避重试间隔（秒）：第 n 次失败后取第 n 个，超出后回落到常规续期周期。
     * 常规续期周期是 registerExpires 的 2/3（默认 40 分钟），失败后等这么久不利于排障与自愈。
     */
    private static final long[] RETRY_BACKOFF_SECONDS = {30L, 60L, 120L, 300L};

    private final ScheduledExecutorService executor =
        Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "cascade-register");
            t.setDaemon(true);
            return t;
        });

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * 启动调度。改用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：
     * 注册任务以 initialDelay=0 立即触发，必须确保此刻
     * <ul>
     *   <li>框架 {@code ClientCommandSender.INSTANCE} 已由 ApplicationContextAware 注入（否则 REGISTER 抛 "尚未初始化"）；</li>
     *   <li>{@code ServerStart}(CommandLineRunner) 已绑定 SIP 监听点；</li>
     *   <li>建表已完成。</li>
     * </ul>
     * 这些都在容器 ready 后才全部就绪，@PostConstruct 阶段均无保证。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshRegistrations();
    }

    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 公开 API                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * 批量刷新：加载所有 enabled=1 平台，为尚未调度的平台启动任务。
     * 由外部也可直接调用（测试 / 管理端手动触发）。
     */
    public void refreshRegistrations() {
        CascadePlatformDTO query = new CascadePlatformDTO();
        query.setEnabled(1);
        List<CascadePlatformDTO> platforms;
        try {
            platforms = cascadePlatformManager.getPage(query, 1, 200).getRecords();
        } catch (Exception e) {
            // 空库 + @PostConstruct 竞态：tb_cascade_platform 可能尚未建出（与 SqliteSchemaInitializer
            // 无依赖顺序保证）。缺表/查询失败时跳过本次刷新并告警，不把次生 DB 异常上抛成启动阻断。
            log.warn("级联平台加载失败（疑似建表未就绪或空库），跳过本次刷新：{}", e.getMessage());
            return;
        }

        for (CascadePlatformDTO p : platforms) {
            if (!registerTasks.containsKey(p.getPlatformId())) {
                startPlatform(p);
            }
        }
        log.info("级联调度刷新完成: 启用平台={}, 注册任务={}, 保活任务={}",
            platforms.size(), registerTasks.size(), keepaliveTasks.size());
    }

    /** 运行时新增 / 重启单个平台的注册 + 保活任务（方案 4.4 / C1）。 */
    public void startPlatform(CascadePlatformDTO platform) {
        /* 只取消旧任务，不向上级发注销：update 场景下紧接着就要重新注册，注销会与注册互相覆盖 */
        stopPlatform(platform.getId(), false);
        /* 重新启用时清掉可能残留的注销标记，否则首个 REGISTER 会被当成注销(Expires=0) */
        cascadeUnregisterService.clear(platform.getPlatformId());
        scheduleRegister(platform);
        scheduleKeepalive(platform);
        log.info("级联调度已启动: platformId={}, target={}:{}, localClientId={}@{}:{}",
            platform.getPlatformId(), platform.getPlatformIp(), platform.getPlatformPort(),
            platform.getLocalClientId(), platform.getLocalIp(), platform.getLocalPort());
    }

    /** 运行时停止平台任务（注册 + 保活），置为离线，并向级联上级注销（方案 4.4）。 */
    public void stopPlatform(Long id) {
        stopPlatform(id, true);
    }

    /**
     * 停止平台任务。
     *
     * @param notifyPeer 是否向级联上级发注销(Expires=0)。停用/删除传 true；仅重建任务（update）传 false，
     *                   否则注销请求会与紧随其后的注册互相覆盖，上级侧最终 Contact 不可预期
     */
    public void stopPlatform(Long id, boolean notifyPeer) {
        if (id == null) return;
        CascadePlatformDTO dto = cascadePlatformManager.getById(id);
        if (dto == null) return;

        /* 先注销上级登记：上级会把该平台的 Contact 清掉，后续变更 IP/端口不会打到旧地址 */
        if (notifyPeer
            && !Objects.equals(dto.getRegisterStatus(), CascadeConstant.RegisterStatus.OFFLINE)) {
            cascadeUnregisterService.unregister(dto);
        }

        cancel(registerTasks.remove(dto.getPlatformId()));
        cancel(keepaliveTasks.remove(dto.getPlatformId()));
        retryCounts.remove(dto.getPlatformId());
        lastRegisterAt.remove(dto.getPlatformId());
        peerAliveTracker.reset(dto.getPlatformId());
        cascadePlatformManager.updateRegisterStatus(id, CascadeConstant.RegisterStatus.OFFLINE);
        log.info("级联调度已停止: platformId={}", dto.getPlatformId());
    }

    /* ------------------------------------------------------------------ */
    /* 内部实现                                                             */
    /* ------------------------------------------------------------------ */

    /** 注册续期：按 registerExpires 的 2/3 周期重发 REGISTER（最短 60s），提前续期避免边界过期。 */
    private void scheduleRegister(CascadePlatformDTO platform) {
        long expires = platform.getRegisterExpires() != null ? platform.getRegisterExpires() : 3600L;
        long period = Math.max(60L, expires * 2 / 3);
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
            () -> doRegister(platform), 0, period, TimeUnit.SECONDS);
        registerTasks.put(platform.getPlatformId(), future);
    }

    /** 保活心跳：独立的 Keepalive MESSAGE 周期任务（默认 60s），同一周期内顺带判定上级是否失联。 */
    private void scheduleKeepalive(CascadePlatformDTO platform) {
        long interval = platform.getKeepaliveInterval() != null
            ? platform.getKeepaliveInterval() : CascadeConstant.DEFAULT_KEEPALIVE_INTERVAL;
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
            () -> {
                doKeepalive(platform);
                checkKeepaliveTimeout(platform);
            }, interval, interval, TimeUnit.SECONDS);
        keepaliveTasks.put(platform.getPlatformId(), future);
    }

    private void doRegister(CascadePlatformDTO platform) {
        /* 周期续期、退避重试、心跳自愈可能同时触发：短时间内重复发 REGISTER 只会互相打断 */
        if (tooSoonToRegister(platform.getPlatformId())) {
            return;
        }

        long   startAt = System.currentTimeMillis();
        String target  = platform.getPlatformIp() + ":" + platform.getPlatformPort();
        try {
            FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
            ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
            log.info("发起级联注册: localClientId={}@{}:{} -> sip:{}@{}, transport={}, charset={}, expires={}s, 连续失败={}",
                platform.getLocalClientId(), platform.getLocalIp(), platform.getLocalPort(),
                platform.getPlatformId(), target, platform.getTransport(), platform.getCharset(),
                platform.getRegisterExpires(), retryCounts.getOrDefault(platform.getPlatformId(), 0));
            cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.REGISTERING);
            String callId = ClientCommandSender.sendRegisterCommand(from, to, platform.getRegisterExpires());
            log.info("级联 REGISTER 已发送: platformId={}, localClientId={}, target={}, callId={}, cost={}ms",
                platform.getPlatformId(), platform.getLocalClientId(), target, callId,
                System.currentTimeMillis() - startAt);
            scheduleRegisterTimeoutCheck(platform, callId);
        } catch (Exception e) {
            log.error("级联注册失败: platformId={}, target={}, localClientId={}, err={}",
                platform.getPlatformId(), target, platform.getLocalClientId(), e.getMessage(), e);
            cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.FAILED);
            markRegisterFailure(platform, "发送 REGISTER 异常: " + e.getMessage());
        }
    }

    private void scheduleRegisterTimeoutCheck(CascadePlatformDTO platform, String callId) {
        long timeout = Math.max(0L, registerTimeoutSeconds);
        executor.schedule(() -> {
            try {
                CascadePlatformDTO current = cascadePlatformManager.getById(platform.getId());
                if (current != null && Objects.equals(current.getRegisterStatus(), CascadeConstant.RegisterStatus.REGISTERING)) {
                    log.warn("级联注册响应超时: platformId={}, localClientId={}, target={}:{}, callId={}, timeout={}s",
                        platform.getPlatformId(), platform.getLocalClientId(),
                        platform.getPlatformIp(), platform.getPlatformPort(), callId, timeout);
                    cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.FAILED);
                    markRegisterFailure(platform, "REGISTER 响应超时(" + timeout + "s)");
                }
            } catch (Exception e) {
                log.warn("级联注册超时检查失败: platformId={}, callId={}, err={}",
                    platform.getPlatformId(), callId, e.getMessage());
            }
        }, timeout, TimeUnit.SECONDS);
    }

    /* ------------------------------------------------------------------ */
    /* 失败可见性与快速重试                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * 注册成功回调（由 {@code CascadeClientRegisterListener} 调用）：清零重试计数并清除失败原因。
     *
     * @param platform 注册成功的平台
     */
    public void markRegisterSuccess(CascadePlatformDTO platform) {
        if (platform == null || platform.getPlatformId() == null) {
            return;
        }
        retryCounts.remove(platform.getPlatformId());
        peerAliveTracker.markRegisterSuccess(platform.getPlatformId());
        updateLastError(platform, "");
    }

    /**
     * 注册失败回调：把失败原因写入 extend 供前端展示，并按退避策略安排一次快速重试。
     *
     * @param platform 注册失败的平台
     * @param reason   失败原因（写给运维看，不得包含密码等敏感信息）
     */
    public void markRegisterFailure(CascadePlatformDTO platform, String reason) {
        if (platform == null || platform.getPlatformId() == null) {
            return;
        }
        updateLastError(platform, reason);
        scheduleRetry(platform, reason);
    }

    /**
     * 失败后的退避重试：按 30s / 60s / 120s / 300s 追加一次性任务，超出次数后回落到常规续期周期。
     *
     * <p>重试前重新读库，避免用旧快照重新注册；平台已停用或已删除则放弃。</p>
     *
     * @param platform 注册失败的平台快照
     * @param reason   失败原因
     */
    public void scheduleRetry(CascadePlatformDTO platform, String reason) {
        String platformKey = platform.getPlatformId();
        int    attempt     = retryCounts.merge(platformKey, 1, Integer::sum);
        if (attempt > RETRY_BACKOFF_SECONDS.length) {
            retryCounts.remove(platformKey);
            log.warn("级联注册连续失败 {} 次，转入常规续期周期: platformId={}, lastReason={}",
                RETRY_BACKOFF_SECONDS.length, platformKey, reason);
            return;
        }
        long delaySeconds = retryDelaySeconds(attempt);
        log.warn("级联注册失败，{}s 后第 {} 次快速重试: platformId={}, reason={}",
            delaySeconds, attempt, platformKey, reason);
        executor.schedule(() -> {
            try {
                CascadePlatformDTO latest = cascadePlatformManager.getById(platform.getId());
                if (latest != null && Integer.valueOf(1).equals(latest.getEnabled())) {
                    doRegister(latest);
                }
            } catch (Exception e) {
                log.warn("级联注册重试调度失败: platformId={}, err={}", platformKey, e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 计算失败后的重试间隔（秒）：第 1 次失败 30s，逐次翻倍，最大 300s。
     *
     * @param attempt 连续失败次数（从 1 开始）
     * @return 重试间隔秒数
     */
    static long retryDelaySeconds(int attempt) {
        if (attempt <= 0) {
            return RETRY_BACKOFF_SECONDS[0];
        }
        int index = Math.min(attempt, RETRY_BACKOFF_SECONDS.length) - 1;
        return RETRY_BACKOFF_SECONDS[index];
    }

    /**
     * 把失败原因合并进 extend 字段（不新增表结构），便于前端与运维直接看到"为什么离线"。
     *
     * <p>写入失败只告警，不影响调度主流程；extend 非法 JSON 时重置为空对象后写入。</p>
     *
     * @param platform 出问题的平台
     * @param error    失败原因；空串表示清除（注册成功）
     */
    private void updateLastError(CascadePlatformDTO platform, String error) {
        try {
            CascadePlatformDTO latest = cascadePlatformManager.getById(platform.getId());
            if (latest == null) {
                return;
            }
            JSONObject extend;
            try {
                extend = StringUtils.isBlank(latest.getExtend())
                    ? new JSONObject() : JSON.parseObject(latest.getExtend());
            } catch (Exception parseError) {
                log.warn("级联平台 extend 非法 JSON，重置后写入失败原因: platformId={}, extend={}",
                    platform.getPlatformId(), latest.getExtend());
                extend = new JSONObject();
            }
            extend.put(CascadeConstant.EXTEND_LAST_ERROR, error == null ? "" : error);
            extend.put(CascadeConstant.EXTEND_LAST_ERROR_AT, System.currentTimeMillis());

            CascadePlatformDTO patch = new CascadePlatformDTO();
            patch.setId(platform.getId());
            patch.setExtend(extend.toJSONString());
            cascadePlatformManager.update(patch);
        } catch (Exception e) {
            log.warn("级联注册失败原因写入失败: platformId={}, err={}", platform.getPlatformId(), e.getMessage());
        }
    }

    /** 保活心跳：仅在平台 ONLINE 时发 Keepalive MESSAGE；离线交由注册续期任务恢复。 */
    private void doKeepalive(CascadePlatformDTO platform) {
        try {
            CascadePlatformDTO current = cascadePlatformManager.getById(platform.getId());
            if (current == null
                || !Objects.equals(current.getRegisterStatus(), CascadeConstant.RegisterStatus.ONLINE)) {
                return;
            }
            FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
            ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
            ClientCommandSender.sendKeepaliveCommand(from, to, platform.getLocalClientId());
            log.debug("级联保活已发送: platformId={}, target={}:{}",
                platform.getPlatformId(), platform.getPlatformIp(), platform.getPlatformPort());
        } catch (Exception e) {
            log.warn("级联保活失败: platformId={}, target={}:{}, err={}",
                platform.getPlatformId(), platform.getPlatformIp(), platform.getPlatformPort(), e.getMessage());
        }
    }

    /**
     * 抑制重复注册：周期续期、退避重试、心跳自愈三条路径共用同一发送入口，
     * 短时间内连发 REGISTER 会让上级看到同一 Call-ID 之外的多个注册事务，互相干扰。
     *
     * @return true 表示距上次发送过近，本次应跳过
     */
    private boolean tooSoonToRegister(String platformId) {
        if (platformId == null) {
            return false;
        }
        long  now  = System.currentTimeMillis();
        Long  last = lastRegisterAt.get(platformId);
        if (last != null && now - last < MIN_REGISTER_INTERVAL_MS) {
            log.debug("距上次 REGISTER 不足 {}s，跳过本次发送: platformId={}",
                MIN_REGISTER_INTERVAL_MS / 1000, platformId);
            return true;
        }
        lastRegisterAt.put(platformId, now);
        return false;
    }

    /**
     * 心跳超时判定与自愈。
     *
     * <p>判据是"最近一次收到该平台响应的时间"（由 {@code CascadeMessageResponseProcessor} /
     * {@code CascadeRegisterAuthProcessor} 登记），超过 {@code keepaliveInterval ×
     * keepaliveTimeoutCount} 即认为上级失联：置 OFFLINE、写失败原因、清存活基线后立即重注册。
     *
     * <p>保护：若从未观察到该平台对 MESSAGE 的响应（部分上级不回 200），则只告警不改状态，
     * 否则会对这类平台造成反复重注册。
     */
    private void checkKeepaliveTimeout(CascadePlatformDTO platform) {
        String platformId = platform.getPlatformId();
        if (platformId == null) {
            return;
        }
        CascadePlatformDTO current;
        try {
            current = cascadePlatformManager.getById(platform.getId());
        } catch (Exception e) {
            log.debug("心跳超时检查读库失败: platformId={}, err={}", platformId, e.getMessage());
            return;
        }
        if (current == null
            || !Objects.equals(current.getRegisterStatus(), CascadeConstant.RegisterStatus.ONLINE)) {
            return;
        }

        long interval = current.getKeepaliveInterval() != null
            ? current.getKeepaliveInterval() : CascadeConstant.DEFAULT_KEEPALIVE_INTERVAL;
        int  count     = Math.max(2, keepaliveTimeoutCount);
        long lastAlive = peerAliveTracker.lastAliveAt(platformId);
        if (lastAlive <= 0) {
            return;
        }
        long now    = System.currentTimeMillis();
        long idleMs = now - lastAlive;

        if (!peerAliveTracker.isKeepaliveAnswered(platformId)) {
            /* 从未见过 MESSAGE 响应：给足宽限期，只提示不判定 */
            long graceMs = interval * count * 4 * 1000L;
            if (idleMs > graceMs) {
                log.warn("级联心跳始终无应答（上级可能不回 MESSAGE 200，按策略不判离线）: platformId={}, "
                        + "无响应 {}s, 宽限 {}s",
                    platformId, idleMs / 1000, graceMs / 1000);
            }
            return;
        }

        long thresholdMs = interval * count * 1000L;
        if (idleMs <= thresholdMs) {
            return;
        }

        log.warn("级联心跳超时，判定上级失联并自愈重注册: platformId={}, 无响应 {}s, 阈值 {}s(interval={}s×{}), "
                + "target={}:{}",
            platformId, idleMs / 1000, thresholdMs / 1000, interval, count,
            current.getPlatformIp(), current.getPlatformPort());
        cascadePlatformManager.updateRegisterStatus(current.getId(), CascadeConstant.RegisterStatus.OFFLINE);
        updateLastError(current, "心跳超时(" + (idleMs / 1000) + "s 无响应)，已自愈重新注册");
        peerAliveTracker.reset(platformId);
        retryCounts.remove(platformId);
        /* 清掉发送时间戳，允许紧接着发一次 REGISTER（不受最小间隔限制） */
        lastRegisterAt.remove(platformId);
        doRegister(current);
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    void setRegisterTimeoutSeconds(long registerTimeoutSeconds) {
        this.registerTimeoutSeconds = registerTimeoutSeconds;
    }
}
