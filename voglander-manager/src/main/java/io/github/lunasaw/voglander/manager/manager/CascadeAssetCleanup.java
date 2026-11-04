package io.github.lunasaw.voglander.manager.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService;
import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService.RevokeResult;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishKeys;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.PublishObjectType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.manager.code.CascadeCodeBuilder;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePublishDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地资产（设备 / 通道 / 组织）被删除时，撤掉它们在发布层的记录与上级目录里的节点。
 *
 * <p><b>为什么必须单独一个组件</b>：删除入口分散在 {@link DeviceChannelManager}（通道）、
 * 设备删除编排（设备）与 {@link CascadeOrgManager}（组织）三处，谁都拿不到「这个对象的发布行是怎么配的」；
 * 而能回答这个问题的 {@link CascadePublishedAssetResolver} 反过来依赖这几个管理器，
 * 直接注入就是循环依赖。这里只依赖发布管理器 + 事件发布器 + 出站撤销通道，三条路径共用同一份删法。
 *
 * <p><b>为什么删发布行，而不是只置停用</b>：发布行的语义是「把某个本地对象推给某个上级」，
 * 本地对象都没了，这张行只剩孤儿——组装器会跳过并告警，上级那边却仍留着上次推上去的节点，
 * 页面上还会显示一条指向不存在通道的推送记录。停用的语义是「资产还在，但暂不推」，不适用。
 *
 * <p><b>为什么必须发 {@code DEL}</b>：完整快照是增量 upsert 语义，上级不会因为节点「这次没出现」
 * 就删除它（见 {@code CascadeEventBridge}），不显式推一次删除，节点会永久留在上级目录里。
 *
 * <p><b>两类删除，两条通道</b>：
 * <ul>
 *   <li><b>用户在页面删本地资产</b>（{@code revokeXxx}）：先向上级取消上报并等其 2xx，
 *       任一平台没确认就抛异常、本地一条都不动——先删本地再通知上级会留下「本地已无、上级仍有」的
 *       悬挂节点，上级还能对着一路不存在的通道发起点播；</li>
 *   <li><b>设备自己上报了 Catalog {@code DEL}</b>（{@code purgeChannel}）：本端只是跟随台账变化，
 *       拦下来会让通道删不掉、与设备台账长期不一致，因此沿用事件链路异步通知、不阻断。</li>
 * </ul>
 *
 * <p><b>同步撤销为什么不走事件</b>：{@code LocalCascadeCatalogChangeEvent} 的监听者是
 * {@code @Async} 的，拿不到结果，无法回答「上级确认了吗」；所以主动路径直连
 * {@link CascadeCatalogRevokeService}，只有被动路径继续用事件。
 *
 * <p><b>为什么提交后才发、且一个平台只发一个事件</b>（被动路径）：{@code CascadeEventBridge}
 * 是异步监听者，提交前发出去它会读到删前的数据、把节点又推回上级；同一批节点拆成多个事件则会因为
 * {@code sipNotifierExecutor} 是多线程而乱序，父节点先消失会让子节点的删除被部分上级忽略。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeAssetCleanup {

    /** 目录节点变更类型：删除 */
    private static final String EVENT_DELETE  = "DEL";

    /** 变更范围：通道（仅用于日志定位） */
    private static final String SCOPE_CHANNEL = "CHANNEL";

    /** 变更范围：设备及其下通道（仅用于日志定位） */
    private static final String SCOPE_DEVICE  = "DEVICE";

    /** 变更范围：组织（仅用于日志定位） */
    private static final String SCOPE_ORG     = "ORG";

    private final CascadePublishManager cascadePublishManager;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 向上级同步撤销上报的出站通道。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入：实现 bean 受 {@code sip.client.enabled} 约束，
     * 关掉出站能力时它不存在。此时根本没有向上级发过报文，上级目录里也不会有这些节点，
     * 按「无人可确认」放行而不是拦住删除。
     */
    private final ObjectProvider<CascadeCatalogRevokeService> revokeServiceProvider;

    // ------------------------------------------------- 主动删除：先取消上报，确认后才清

    /**
     * 撤销并清除一路通道的发布关系。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码
     * @return 删除的发布行条数
     * @throws IllegalArgumentException 有平台未确认撤销——此时本地数据一条都没动
     */
    public int revokeChannel(String deviceId, String channelId) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(channelId)) {
            return 0;
        }
        return revokeThenPurge(channelId, SCOPE_CHANNEL,
            channelRows(deviceId, List.of(channelId)));
    }

    /**
     * 撤销并清除一批通道（同一台设备下）的发布关系。
     *
     * <p>按设备分组调用：每台设备只查一次发布表，不会退化成逐通道回表。
     *
     * @param deviceId   设备编码
     * @param channelIds 通道编码集合
     * @return 删除的发布行条数
     * @throws IllegalArgumentException 有平台未确认撤销——此时本地数据一条都没动
     */
    public int revokeChannels(String deviceId, Collection<String> channelIds) {
        if (StringUtils.isBlank(deviceId) || channelIds == null || channelIds.isEmpty()) {
            return 0;
        }
        return revokeThenPurge(deviceId, SCOPE_CHANNEL, channelRows(deviceId, channelIds));
    }

    /**
     * 撤销并清除一台设备及其名下全部通道的发布关系。
     *
     * <p>通道节点排在设备节点之前：先删父会让子节点变成孤儿，部分上级会忽略对孤儿节点的后续删除。
     *
     * @param deviceId 设备编码
     * @return 删除的发布行条数
     * @throws IllegalArgumentException 有平台未确认撤销——此时本地数据一条都没动
     */
    public int revokeDevice(String deviceId) {
        if (StringUtils.isBlank(deviceId)) {
            return 0;
        }
        List<CascadePublishDTO> rows = new ArrayList<>(listChannelRowsOfDevice(deviceId));
        rows.addAll(cascadePublishManager.listByObjectKeys(
            PublishObjectType.DEVICE.name(), List.of(deviceId)));
        return revokeThenPurge(deviceId, SCOPE_DEVICE, rows);
    }

    /**
     * 撤销并清除一个组织的发布关系。
     *
     * <p>调用方（{@link CascadeOrgManager#delete}）已保证组织下没有子组织、设备与通道，
     * 这里只需要撤组织自己那批节点。
     *
     * @param orgCode 组织编码
     * @return 删除的发布行条数
     * @throws IllegalArgumentException 有平台未确认撤销——此时本地数据一条都没动
     */
    public int revokeOrg(String orgCode) {
        if (StringUtils.isBlank(orgCode)) {
            return 0;
        }
        return revokeThenPurge(orgCode, SCOPE_ORG, cascadePublishManager.listByObjectKeys(
            PublishObjectType.ORG.name(), List.of(orgCode)));
    }

    /**
     * 只向上级撤销一批节点、等其确认，<b>不动发布行</b>。
     *
     * <p>给「组织从某个平台撤回」这类<b>只影响一路平台</b>的场景用：本地发布行的启停由调用方决定
     * （撤回是置停用，不是删行——删了编码覆盖就一起丢了），而这个组件只负责「先让上级删掉节点」。
     *
     * <p><b>为什么不在本组件里一起置停用</b>：发布行怎么改属于业务语义（撤回只停自己那行、
     * 连停用还是删行），与「先等上级确认」是两个独立决策。混在一起会让删除链路与撤回链路
     * 各写一套发布行操作，长期漂移。
     *
     * <p>未确认时直接抛、本地一行不改：上级没删而本地先停用，重试时用户看不出哪一步已经生效；
     * 反过来上级已删而本地置停用失败，重试是幂等的（上级对不存在的节点仍回 2xx）。
     *
     * @param platformId 上级平台编码
     * @param nodes      待撤销节点，须已按「叶子 → 根」排好
     * @param action     动作描述，拼进失败提示（如「撤回组织【园区】」）
     * @throws IllegalArgumentException 上级未确认
     */
    public void revokeCatalogNodes(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes,
        String action) {
        if (StringUtils.isBlank(platformId) || nodes == null || nodes.isEmpty()) {
            return;
        }
        CascadeCatalogRevokeService revokeService = revokeServiceProvider.getIfAvailable();
        if (revokeService == null) {
            log.warn("出站通道未启用（sip.client.enabled=false），跳过向上级撤销: platformId={}, action={}",
                platformId, action);
            return;
        }
        RevokeResult result = revokeService.revoke(platformId, nodes);
        if (result.satisfied()) {
            log.info("上级已确认撤销: platformId={}, action={}, 节点数={}, 结论={}",
                platformId, action, nodes.size(), result.reason());
            return;
        }
        String reason = result.failureReason();
        log.warn("上级未确认撤销: platformId={}, action={}, 节点数={}, 结论={}",
            platformId, action, nodes.size(), result.reason());
        Assert.isTrue(false, () -> action + "失败：平台 " + platformId + " " + reason
            + "，本地配置未变更，请确认上级在线后重试");
    }

    // ------------------------------------------------------- 被动删除：不阻断本地流程

    /**
     * 清除一路通道的发布关系，并在事务提交后按平台异步通知上级 {@code DEL}。
     *
     * <p>只给「设备上报 Catalog {@code DEL}」这类被动场景用：本端跟随设备台账删通道，
     * 等上级确认既没有意义（节点本来就该由设备侧的删除带走）也会让通道永远删不掉。
     *
     * @param deviceId  设备编码
     * @param channelId 通道编码
     * @return 删除的发布行条数
     */
    public int purgeChannel(String deviceId, String channelId) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(channelId)) {
            return 0;
        }
        return purge(channelId, SCOPE_CHANNEL, channelRows(deviceId, List.of(channelId)));
    }

    // ----------------------------------------------------------------------- 内部

    /** 一批通道的发布行；同一台设备下只回表一次。 */
    private List<CascadePublishDTO> channelRows(String deviceId, Collection<String> channelIds) {
        List<String> keys = new ArrayList<>(channelIds.size());
        for (String channelId : channelIds) {
            if (StringUtils.isNotBlank(channelId)) {
                keys.add(PublishKeys.channel(deviceId, channelId.trim()));
            }
        }
        return cascadePublishManager.listByObjectKeys(PublishObjectType.CHANNEL.name(), keys);
    }

    /**
     * 设备名下全部通道的发布行。
     *
     * <p>{@code object_key} 是拼出来的（{@code deviceId:channelId}），没有设备列可查，只能按前缀捞；
     * 前缀已经带上分隔符，仍按 {@link PublishKeys#channelDeviceId} 复核一次，挡住历史上缺分隔符的脏键。
     */
    private List<CascadePublishDTO> listChannelRowsOfDevice(String deviceId) {
        List<CascadePublishDTO> rows = new ArrayList<>();
        for (CascadePublishDTO row : cascadePublishManager.listByObjectKeyPrefix(
            PublishObjectType.CHANNEL.name(), deviceId + PublishKeys.CHANNEL_SEPARATOR)) {
            if (deviceId.equals(PublishKeys.channelDeviceId(row.getObjectKey()))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 先向上级取消上报并等确认，全部确认后才删发布行。
     *
     * <p>未确认时什么都不删、直接抛：半途清掉一部分发布行会让「上级目录」与「发布层」两边都不完整，
     * 用户重试时也看不出哪些已经撤过。
     */
    private int revokeThenPurge(String anchorKey, String scope, List<CascadePublishDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, List<LocalCascadeCatalogChangeEvent.Node>> nodesByPlatform = new LinkedHashMap<>();
        collectNodes(nodesByPlatform, rows);
        revokeOrThrow(nodesByPlatform);
        return deleteRows(anchorKey, scope, rows);
    }

    /** 删发布行 + 提交后按平台发 {@code DEL}（被动路径）。 */
    private int purge(String anchorKey, String scope, List<CascadePublishDTO> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, List<LocalCascadeCatalogChangeEvent.Node>> nodesByPlatform = new LinkedHashMap<>();
        collectNodes(nodesByPlatform, rows);
        int deleted = deleteRows(anchorKey, scope, rows);
        publishAfterCommit(anchorKey, scope, nodesByPlatform);
        return deleted;
    }

    private int deleteRows(String anchorKey, String scope, List<CascadePublishDTO> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (CascadePublishDTO row : rows) {
            if (row.getId() != null) {
                ids.add(row.getId());
            }
        }
        int deleted = cascadePublishManager.deleteByIds(ids);
        log.info("清理级联发布关系: scope={}, anchor={}, 发布行={}", scope, anchorKey, deleted);
        return deleted;
    }

    /**
     * 逐平台撤销，任一平台未确认即抛出可读原因。
     *
     * <p>把所有失败平台一次报全，而不是撞到第一个就停：多平台级联时用户需要知道
     * 到底是哪几路没撤干净，否则只能反复试。
     */
    private void revokeOrThrow(Map<String, List<LocalCascadeCatalogChangeEvent.Node>> nodesByPlatform) {
        if (nodesByPlatform.isEmpty()) {
            return;
        }
        CascadeCatalogRevokeService revokeService = revokeServiceProvider.getIfAvailable();
        if (revokeService == null) {
            log.warn("出站通道未启用（sip.client.enabled=false），跳过向上级取消上报: 平台数={}",
                nodesByPlatform.size());
            return;
        }
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, List<LocalCascadeCatalogChangeEvent.Node>> entry : nodesByPlatform.entrySet()) {
            RevokeResult result = revokeService.revoke(entry.getKey(), entry.getValue());
            if (!result.satisfied()) {
                failures.add("平台 " + entry.getKey() + "：" + result.failureReason());
            }
        }
        Assert.isTrue(failures.isEmpty(),
            () -> "上级未确认取消上报，本地数据未删除（" + String.join("；", failures) + "），"
                + "请确认上级平台在线后重试");
    }

    /**
     * 按平台归集待删节点：停用的发布行上级那里早就没有这个节点，不必再撤销。
     *
     * <p><b>父编码要换成该平台的码</b>：{@code parent_key} 存的是<b>本地</b>组织编码（平台无关），
     * 而上级目录里挂的是那一级的 {@code publish_code}。不映射就会指着一个上级不认识的父节点，
     * 节点本身靠 {@code DeviceID} 还是能删掉，但报文与目录结构对不上，排查时需要额外一步推理。
     */
    private void collectNodes(Map<String, List<LocalCascadeCatalogChangeEvent.Node>> sink,
        List<CascadePublishDTO> rows) {
        Map<String, List<CascadePublishDTO>> rowsByPlatform = new LinkedHashMap<>();
        for (CascadePublishDTO row : rows) {
            if (!Integer.valueOf(1).equals(row.getEnabled()) || StringUtils.isBlank(row.getPlatformId())) {
                continue;
            }
            if (StringUtils.isBlank(cascadeIdOf(row))) {
                log.warn("发布行无法推导上报编码，跳过删除通知: id={}, objectKey={}",
                    row.getId(), row.getObjectKey());
                continue;
            }
            rowsByPlatform.computeIfAbsent(row.getPlatformId(), key -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<CascadePublishDTO>> entry : rowsByPlatform.entrySet()) {
            Map<String, CascadePublishDTO> orgPublishByKey = cascadePublishManager.indexByObjectKey(
                entry.getKey(), PublishObjectType.ORG.name(), false);
            List<LocalCascadeCatalogChangeEvent.Node> nodes = new ArrayList<>(entry.getValue().size());
            for (CascadePublishDTO row : entry.getValue()) {
                String cascadeId = cascadeIdOf(row);
                nodes.add(new LocalCascadeCatalogChangeEvent.Node(cascadeId,
                    StringUtils.defaultIfBlank(row.getPublishName(), cascadeId),
                    parentCodeOf(row, orgPublishByKey)));
            }
            sink.put(entry.getKey(), nodes);
        }
    }

    /** 删除报文的父编码：父节点是组织时换成该平台的编码覆盖，留空由发送方回落域节点。 */
    private String parentCodeOf(CascadePublishDTO row, Map<String, CascadePublishDTO> orgPublishByKey) {
        String parentKey = StringUtils.trimToNull(row.getParentKey());
        if (parentKey == null) {
            return null;
        }
        CascadePublishDTO parentPublish = orgPublishByKey.get(parentKey);
        return CascadeCodeBuilder.effectiveCode(parentPublish == null ? null : parentPublish.getPublishCode(),
            parentKey);
    }

    /**
     * 上级眼里的节点编码：编码覆盖优先，未覆盖时通道取通道段、设备与组织取对象编码。
     *
     * <p>走 {@link CascadeCodeBuilder#effectiveCode} 而不是就地写 {@code defaultIfBlank}：
     * 删除链路与目录组装必须算出同一个码，否则会去删上级从来没见过、或与别的节点同名的节点。
     * 这里只读不生成——DEL 路径若就地发新号，删掉的是一个上级根本没收到过的编码。
     */
    private String cascadeIdOf(CascadePublishDTO row) {
        return CascadeCodeBuilder.effectiveCode(row.getPublishCode(),
            CascadeCodeBuilder.localCodeOf(row.getObjectType(), row.getObjectKey()));
    }

    /** 事务内注册到提交之后发；没有事务（脚本、单测）则直接发。 */
    private void publishAfterCommit(String anchorKey, String scope,
        Map<String, List<LocalCascadeCatalogChangeEvent.Node>> nodesByPlatform) {
        if (eventPublisher == null || nodesByPlatform.isEmpty()) {
            return;
        }
        Runnable publish = () -> {
            for (Map.Entry<String, List<LocalCascadeCatalogChangeEvent.Node>> entry : nodesByPlatform.entrySet()) {
                eventPublisher.publishEvent(new LocalCascadeCatalogChangeEvent(
                    entry.getKey(), scope, anchorKey, EVENT_DELETE, entry.getValue()));
                log.info("级联资产已删除，通知上级删节点: platformId={}, scope={}, anchor={}, 节点数={}",
                    entry.getKey(), scope, anchorKey, entry.getValue().size());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
            return;
        }
        publish.run();
    }
}
