package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.HashMap;
import java.util.Map;

import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterSuccessEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterFailureEvent;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.common.event.SseRelayEvent;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 GB28181 客户端注册事件，同步更新 tb_cascade_platform.register_status，
 * 并通过 SSE topic {@code cascade.register} 推送状态变更给前端（前端列表实时刷新）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeClientRegisterListener {

    private final CascadePlatformManager    cascadePlatformManager;
    private final ApplicationEventPublisher eventPublisher;
    private final CascadeClientScheduler    cascadeClientScheduler;
    private final CascadeUnregisterService  cascadeUnregisterService;

    /** 目录快照推送器软注入：{@code sip.client.enabled=false} 时该 Bean 不存在。 */
    private final ObjectProvider<CascadeNotifyPublisher> notifyPublisherProvider;

    @EventListener
    public void onRegisterSuccess(ClientRegisterSuccessEvent event) {
        String eventUserId = event.getUserId();
        CascadePlatformDTO platform = resolvePlatform(eventUserId);
        if (platform == null) {
            log.warn("收到级联注册成功事件但未找到平台配置: eventUserId={}, peerProtocolVersion={}",
                eventUserId, event.getPeerProtocolVersion());
            return;
        }

        /* 注销（Expires=0）的 200 也走这条事件：确认注销后置离线，绝不能标成 ONLINE */
        if (cascadeUnregisterService.isUnregistering(platform.getPlatformId())) {
            cascadeUnregisterService.clear(platform.getPlatformId());
            cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.OFFLINE);
            publishRegisterStatus(platform.getPlatformId(), CascadeConstant.RegisterStatus.OFFLINE);
            log.info("级联注销已完成(Expires=0): platformId={}, localClientId={}, target={}:{}",
                platform.getPlatformId(), platform.getLocalClientId(),
                platform.getPlatformIp(), platform.getPlatformPort());
            return;
        }

        cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.ONLINE);
        cascadeClientScheduler.markRegisterSuccess(platform);
        publishRegisterStatus(platform.getPlatformId(), CascadeConstant.RegisterStatus.ONLINE);
        log.info("级联注册成功: eventUserId={}, platformId={}, localClientId={}, target={}:{}, peerProtocolVersion={}",
            eventUserId, platform.getPlatformId(), platform.getLocalClientId(),
            platform.getPlatformIp(), platform.getPlatformPort(), event.getPeerProtocolVersion());

        pushCatalogSnapshotQuietly(platform.getPlatformId());
    }

    /**
     * 注册成功后补推一次完整目录。
     *
     * <p>上级的 Catalog 订阅与本次 REGISTER 是两条独立链路：应用重启后上级手上的订阅可能仍在有效期，
     * 不会马上重发 SUBSCRIBE，于是它握着空目录一直等到订阅过期。每轮 REGISTER（含续约）顺带推一包
     * 全量目录，既能重启后立即恢复可见性，也让上级目录与本地配置定期对齐。
     *
     * <p>失败只记 warn：目录可见性不该反过来影响注册状态判定。
     */
    private void pushCatalogSnapshotQuietly(String platformId) {
        CascadeNotifyPublisher publisher = notifyPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            publisher.pushCatalogSnapshot(platformId, null);
        } catch (Exception e) {
            log.warn("注册成功后补推目录快照失败: platformId={}, err={}", platformId, e.getMessage());
        }
    }

    @EventListener
    public void onRegisterFailure(ClientRegisterFailureEvent event) {
        String eventUserId = event.getUserId();
        CascadePlatformDTO platform = resolvePlatform(eventUserId);
        if (platform != null) {
            cascadePlatformManager.updateRegisterStatus(platform.getId(), CascadeConstant.RegisterStatus.FAILED);
            /* 失败原因落 extend + 安排退避重试：401/403 多为 localClientId 或密码不匹配，排查提示直接给出 */
            String reason = "REGISTER 失败 statusCode=" + event.getStatusCode() + suggestByStatusCode(event.getStatusCode());
            cascadeClientScheduler.markRegisterFailure(platform, reason);
            publishRegisterStatus(platform.getPlatformId(), CascadeConstant.RegisterStatus.FAILED);
            log.warn("级联注册失败: eventUserId={}, platformId={}, localClientId={}@{}:{}, target={}:{}, statusCode={}, reason={}",
                eventUserId, platform.getPlatformId(), platform.getLocalClientId(),
                platform.getLocalIp(), platform.getLocalPort(),
                platform.getPlatformIp(), platform.getPlatformPort(), event.getStatusCode(), reason);
        } else {
            log.warn("收到级联注册失败事件但未找到平台配置: eventUserId={}, statusCode={}",
                eventUserId, event.getStatusCode());
        }
    }

    /**
     * 按 SIP 状态码给出可操作的排查提示，避免只看到 401/403 却不知道改哪里。
     *
     * @param statusCode REGISTER 响应的 SIP 状态码
     * @return 排查提示；未知状态码返回空串
     */
    private static String suggestByStatusCode(Integer statusCode) {
        if (statusCode == null) {
            return "";
        }
        return switch (statusCode) {
            case 401, 403 -> "（请核对本端客户端ID是否已在上级登记、密码是否正确）";
            case 404 -> "（上级不存在该平台ID，请核对对方平台ID/域）";
            case 408 -> "（上级响应超时，请检查网络与防火墙）";
            default -> "";
        };
    }

    /**
     * 框架注册响应事件的 userId 来自 REGISTER 响应 To 头，通常是上级平台 platformId；
     * 兼容旧测试/旧事件里传 localClientId 的情况，避免状态卡在 REGISTERING。
     */
    private CascadePlatformDTO resolvePlatform(String eventUserId) {
        if (eventUserId == null) {
            return null;
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(eventUserId);
        return platform != null ? platform : cascadePlatformManager.getByLocalClientId(eventUserId);
    }

    /**
     * 推送注册状态变更到 SSE topic {@code cascade.register}。
     *
     * @param platformId     上级平台国标 ID
     * @param registerStatus 注册状态（0离线 1在线 2注册中 3失败）
     */
    private void publishRegisterStatus(String platformId, int registerStatus) {
        Map<String, Object> data = new HashMap<>();
        data.put("platformId", platformId);
        data.put("registerStatus", registerStatus);
        data.put("ts", System.currentTimeMillis());
        eventPublisher.publishEvent(new SseRelayEvent("cascade.register", data));
    }
}
