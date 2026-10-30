package io.github.lunasaw.voglander.web.api.cascade.support;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeSubscribeDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeSubscribeManager;

/**
 * 级联报文「能不能送到」的前置探测。
 *
 * <p><b>为什么单独抽出来</b>：删除报文是单向 MESSAGE，上级没收到就没有任何反馈——
 * 本地记录已经改成停报，上级目录里节点却还在，两边状态永久错位，事后只能靠人工比对发现。
 * 因此在用户按下「取消同步」之前，把投递条件先探明并提示。
 *
 * <p>两个判据都不在本层重算：
 * <ul>
 *   <li>在线与否只看 {@code register_status}：心跳超时、重注册自愈都由
 *       {@code CascadeClientScheduler} 维护该字段，这里重算一套超时阈值只会出现两个真相。</li>
 *   <li>订阅与否看订阅表里 {@code CATALOG} 的 ACTIVE 记录且未过期：上级没订阅时，
 *       我们发出的 Notify 不会进入它的目录处理流程。</li>
 * </ul>
 *
 * <p>探测结果只用于<b>提示</b>，不阻断操作：本地意图照常落库，否则用户会被卡在一个
 * 「上级离线就永远改不了配置」的死路上。
 *
 * @author luna
 */
@Component
public class CascadeDeliveryGate {

    @Autowired
    private CascadePlatformManager  cascadePlatformManager;

    @Autowired
    private CascadeSubscribeManager cascadeSubscribeManager;

    /**
     * 探测某上级平台当前的报文投递条件。
     *
     * @param platformId 上级平台编码；空值返回全 false 且带提示
     */
    public Report check(String platformId) {
        List<String> warnings = new ArrayList<>();
        if (platformId == null || platformId.isBlank()) {
            warnings.add("缺少上级平台编码，无法判断报文能否送达");
            return new Report(false, false, warnings);
        }

        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        boolean online = platform != null
            && platform.getRegisterStatus() != null
            && platform.getRegisterStatus() == CascadeConstant.RegisterStatus.ONLINE;
        if (platform == null) {
            warnings.add("上级平台【" + platformId + "】不存在，删除报文不会发出");
        } else if (!online) {
            warnings.add("上级平台当前不在线，删除报文会丢失，上级目录里的节点不会消失；"
                + "请等上级恢复在线后重新执行一次取消同步");
        }

        boolean subscribed = hasActiveCatalogSubscription(platformId);
        if (!subscribed) {
            warnings.add("上级尚未建立有效的目录订阅，删除报文不会被接收；"
                + "请等上级订阅目录后再执行，或订阅建立后重新执行一次取消同步");
        }
        return new Report(online, subscribed, warnings);
    }

    /**
     * 是否存在生效的目录订阅。
     *
     * <p>订阅表按 {@code (platform_id, sub_type)} 唯一，因此取一条即可；状态必须是
     * ACTIVE，且过期时间已过的一律按失效处理——清理任务不是实时跑的，
     * 只看状态字段会把过期订阅当成有效。
     */
    private boolean hasActiveCatalogSubscription(String platformId) {
        CascadeSubscribeDTO sub = cascadeSubscribeManager
            .getByPlatformAndType(platformId, CascadeConstant.SubType.CATALOG);
        if (sub == null || sub.getStatus() == null
            || sub.getStatus() != CascadeConstant.SubStatus.ACTIVE) {
            return false;
        }
        return sub.getExpireTime() == null || sub.getExpireTime().isAfter(LocalDateTime.now());
    }

    /**
     * 投递条件探测结果。
     *
     * @param platformOnline    上级是否在线（register_status = ONLINE）
     * @param catalogSubscribed 上级是否持有生效的目录订阅
     * @param warnings          风险提示，恒非空
     */
    public record Report(boolean platformOnline, boolean catalogSubscribed, List<String> warnings) {

        /** 报文能否送达：在线且已订阅 */
        public boolean deliverable() {
            return platformOnline && catalogSubscribed;
        }
    }
}