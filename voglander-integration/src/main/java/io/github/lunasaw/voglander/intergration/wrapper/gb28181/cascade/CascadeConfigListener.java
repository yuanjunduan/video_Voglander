package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.lunasaw.gb28181.common.entity.control.DeviceConfigControl;
import io.github.lunasaw.gbproxy.client.api.ConfigListener;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 上级下发的设备配置（Config Download / BasicParam）落地。
 *
 * <p>GB28181 允许上级用 MESSAGE 携带 {@code <BasicParam>} 重设下级的心跳间隔、心跳次数与注册有效期。
 * 不做落地时表现为"上级改了自己的心跳策略，下级仍按旧间隔发送"，长期会导致上级侧误判掉线。
 *
 * <p>落库后立即重建该平台的注册与心跳任务，使新周期立刻生效，无需重启或人工重启平台。
 * 数值有上下界校验：上级配置异常时不落库，避免被改成 0 秒导致心跳风暴。
 *
 * @author luna
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sip.client.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CascadeConfigListener implements ConfigListener {

    /** 心跳间隔允许区间（秒） */
    private static final int MIN_HEARTBEAT_INTERVAL = 5;
    private static final int MAX_HEARTBEAT_INTERVAL = 3600;

    /** 注册有效期允许区间（秒） */
    private static final int MIN_EXPIRES = 60;
    private static final int MAX_EXPIRES = 86400;

    private final CascadePlatformManager cascadePlatformManager;
    private final CascadeClientScheduler  cascadeClientScheduler;

    @Override
    public void onBasicParamConfig(String platformId, DeviceConfigControl control) {
        DeviceConfigControl.BasicParam param = control == null ? null : control.getBasicParam();
        if (param == null) {
            log.debug("收到 BasicParam 配置但无有效参数: platformId={}", platformId);
            return;
        }
        CascadePlatformDTO platform = resolvePlatform(platformId);
        if (platform == null) {
            log.warn("收到 BasicParam 配置但未找到平台: platformId={}, name={}, interval={}, count={}, expiration={}",
                platformId, param.getName(), param.getHeartBeatInterval(), param.getHeartBeatCount(),
                param.getExpiration());
            return;
        }

        Integer interval = parseInRange(param.getHeartBeatInterval(), MIN_HEARTBEAT_INTERVAL, MAX_HEARTBEAT_INTERVAL);
        Integer expires  = parseInRange(param.getExpiration(), MIN_EXPIRES, MAX_EXPIRES);

        CascadePlatformDTO patch = new CascadePlatformDTO();
        patch.setId(platform.getId());
        patch.setKeepaliveInterval(interval);
        patch.setRegisterExpires(expires);
        if (interval == null && expires == null) {
            log.warn("BasicParam 无可用字段（间隔={}, 有效期={}），保持原配置: platformId={}",
                param.getHeartBeatInterval(), param.getExpiration(), platformId);
            return;
        }

        cascadePlatformManager.update(patch);
        log.info("上级重设 BasicParam 已落库: platformId={}, name={}, 心跳间隔={}s(原{}), 有效期={}s(原{}), 心跳次数={}",
            platformId, param.getName(),
            interval != null ? interval : platform.getKeepaliveInterval(), platform.getKeepaliveInterval(),
            expires != null ? expires : platform.getRegisterExpires(), platform.getRegisterExpires(),
            param.getHeartBeatCount());

        /* 重建任务让新周期立刻生效；startPlatform 只取消本地任务，不会向上级发注销 */
        CascadePlatformDTO latest = cascadePlatformManager.getById(platform.getId());
        if (latest != null) {
            cascadeClientScheduler.startPlatform(latest);
        }
    }

    /**
     * 解析并校验数值：非数字或越界都视为无效（返回 null 表示保持原值）。
     *
     * @param raw 上级下发的原始字符串
     * @param min 允许下限（含）
     * @param max 允许上限（含）
     * @return 合法值；无效返回 null
     */
    private Integer parseInRange(String raw, int min, int max) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                log.warn("BasicParam 数值越界，已忽略: value={}, 允许区间=[{}, {}]", value, min, max);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            log.warn("BasicParam 数值无法解析，已忽略: raw={}", raw);
            return null;
        }
    }

    /**
     * 解析平台配置：兼容事件里传上级 platformId 或本端 localClientId 的两种情况。
     */
    private CascadePlatformDTO resolvePlatform(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return null;
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        return platform != null ? platform : cascadePlatformManager.getByLocalClientId(platformId);
    }
}
