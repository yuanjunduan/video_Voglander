package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeRuntimeSnapshotDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeSubscribeDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeSubscribeManager;

/**
 * 级联运行状态汇总器：把目录分片、媒体会话、待确认推送与订阅状态拼成一份快照。
 *
 * <p><b>为什么集中在一个类里</b>：这些状态分散在四个协议组件的内存表中，单个组件只知道自己那一段。
 * 现场排查「上级说目录不全」时真正需要的是同屏对照：分片停在第几包、有没有在途包、
 * 上级当前订阅的 SN 是多少。汇总放在 integration 层是因为 manager 不能反向依赖协议组件。
 *
 * <p>三个来源组件都可能因配置关闭而不存在（{@code sip.client.enabled=false}），
 * 因此一律按可选依赖处理：缺谁就少一块数据，不能让运行状态页把整个上下文带崩。
 *
 * @author luna
 */
@Component
public class CascadeRuntimeMonitor {

    private static final String PHASE_PENDING = "PENDING";
    private static final String PHASE_ACTIVE  = "ACTIVE";

    @Autowired(required = false)
    private CascadeCatalogPager       catalogPager;

    @Autowired(required = false)
    private CascadeMediaInviteListener mediaInviteListener;

    @Autowired(required = false)
    private CascadeNotifyAckTracker    notifyAckTracker;

    @Autowired
    private CascadeSubscribeManager    cascadeSubscribeManager;

    /**
     * 采集一份运行状态快照。
     *
     * @return 快照（各列表恒非 null，便于前端直接渲染）
     */
    public CascadeRuntimeSnapshotDTO snapshot() {
        List<CascadeRuntimeSnapshotDTO.MediaSessionInfo> sessions = mediaInviteListener == null
            ? List.of() : mediaInviteListener.snapshotSessions();
        return new CascadeRuntimeSnapshotDTO(
            System.currentTimeMillis(),
            catalogPager == null ? List.of() : catalogPager.snapshotBatches(),
            catalogPager == null ? List.of() : catalogPager.snapshotInflight(),
            filterPhase(sessions, PHASE_PENDING),
            filterPhase(sessions, PHASE_ACTIVE),
            notifyAckTracker == null ? List.of() : notifyAckTracker.snapshotPending(),
            activeSubscriptions());
    }

    /** 三类订阅合并：上级对目录/告警/位置各有一条 ACTIVE 记录 */
    private List<CascadeSubscribeDTO> activeSubscriptions() {
        List<CascadeSubscribeDTO> result = new ArrayList<>();
        for (CascadeConstant.SubType type : CascadeConstant.SubType.values()) {
            result.addAll(cascadeSubscribeManager.listActiveByType(type));
        }
        return result;
    }

    private List<CascadeRuntimeSnapshotDTO.MediaSessionInfo> filterPhase(
        List<CascadeRuntimeSnapshotDTO.MediaSessionInfo> sessions, String phase) {
        return sessions.stream().filter(s -> phase.equals(s.phase())).toList();
    }
}
