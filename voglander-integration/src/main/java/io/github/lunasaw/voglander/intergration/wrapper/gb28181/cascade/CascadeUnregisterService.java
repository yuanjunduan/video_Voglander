package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联注销（REGISTER + Expires: 0）。
 *
 * <p>停用 / 删除平台时仅取消本地任务是不够的：上级仍会保留上一次注册登记的 Contact。
 * 之后本端改了 IP、端口，上级依旧把 INVITE / 查询打到旧地址，表现为"平台在线但点播无响应"。
 * 因此停用前必须显式注销。
 *
 * <p>注销与注册共用同一条 REGISTER 链路：首包不带 Authorization，上级回 401 时由
 * {@link CascadeRegisterAuthProcessor} 用平台身份重认证——它在重发前会查询本类的
 * {@link #isUnregistering(String)}，把 Expires 置 0。
 *
 * <p>注销标记带 TTL：上级长时间不回应时自动失效，避免影响后续正常注册。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeUnregisterService {

    /** 注销标记有效期：超过则视为失效（上级未回应），不影响后续注册 */
    private static final long PENDING_TTL_MS = 60_000L;

    /** platformId → 标记过期时间（毫秒） */
    private final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>();

    private final CascadeDeviceSupplier cascadeDeviceSupplier;

    /**
     * 发起注销：先打标记再发首包（Expires=0，不带鉴权）。
     *
     * <p>标记必须在发送前设置——上级回 401 可能非常快，晚一步就会按正常有效期重认证。
     *
     * @param platform 待注销平台；为空或编码缺失时直接忽略
     */
    public void unregister(CascadePlatformDTO platform) {
        if (platform == null || platform.getPlatformId() == null) {
            return;
        }
        String platformId = platform.getPlatformId();
        pending.put(platformId, System.currentTimeMillis() + PENDING_TTL_MS);
        try {
            FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
            ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
            String     callId = ClientCommandSender.sendRegisterCommand(from, to, 0);
            log.info("级联注销已发送: platformId={}, localClientId={}@{}:{}, target={}:{}, callId={}",
                platformId, platform.getLocalClientId(), platform.getLocalIp(), platform.getLocalPort(),
                platform.getPlatformIp(), platform.getPlatformPort(), callId);
        } catch (Exception e) {
            log.warn("级联注销发送失败（不影响本地停用）: platformId={}, err={}", platformId, e.getMessage());
        }
    }

    /**
     * 该平台是否处于注销流程中（带惰性过期判断）。
     */
    public boolean isUnregistering(String platformId) {
        if (platformId == null) {
            return false;
        }
        Long expireAt = pending.get(platformId);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireAt) {
            pending.remove(platformId);
            log.debug("级联注销标记已过期，按正常注册处理: platformId={}", platformId);
            return false;
        }
        return true;
    }

    /** 注销得到确认（收到 200）或平台已重新启用时清除标记。 */
    public void clear(String platformId) {
        if (platformId != null) {
            pending.remove(platformId);
        }
    }
}
