package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.event.SseRelayEvent;
import lombok.RequiredArgsConstructor;

/**
 * 级联注册状态 → SSE topic {@code cascade.register} 的唯一发布点。
 *
 * <p>状态来源有两条独立链路：注册/注销结果由 {@link CascadeClientRegisterListener} 从 SIP 事件里拿，
 * 超时与发送异常只有 {@link CascadeClientScheduler} 知道。两边的 payload 必须完全一致
 * （前端按 {@code topic + data.ts + data.platformId} 去重），所以收敛到这里，而不是各写一份 Map。
 */
@Component
@RequiredArgsConstructor
public class CascadeRegisterStatusPublisher {

    /** SSE 主题；前端 {@code useCascadeStatusRefresh} 按此订阅。 */
    public static final String          TOPIC = "cascade.register";

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 推送一次注册状态变更。
     *
     * @param platformId     上级平台国标 ID
     * @param registerStatus 注册状态（0 离线 / 1 在线 / 2 注册中 / 3 失败）
     */
    public void publish(String platformId, int registerStatus) {
        Map<String, Object> data = new HashMap<>(4);
        data.put("platformId", platformId);
        data.put("registerStatus", registerStatus);
        /* ts 参与前端去重键：同一平台的后续事件必须能独立成键，否则会被静默丢弃 */
        data.put("ts", System.currentTimeMillis());
        eventPublisher.publishEvent(new SseRelayEvent(TOPIC, data));
    }
}
