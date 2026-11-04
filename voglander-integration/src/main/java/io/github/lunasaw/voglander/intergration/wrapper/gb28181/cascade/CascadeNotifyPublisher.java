package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sip.header.ContentTypeHeader;
import javax.sip.message.Request;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.lunasaw.gb28181.common.entity.enums.CmdTypeEnum;
import io.github.lunasaw.gb28181.common.entity.notify.DeviceAlarmNotify;
import io.github.lunasaw.gb28181.common.entity.notify.DeviceOtherUpdateNotify;
import io.github.lunasaw.gb28181.common.entity.notify.MobilePositionNotify;
import io.github.lunasaw.gbproxy.client.transmit.cmd.ClientCommandSender;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.sip.common.transmit.SipSender;
import io.github.lunasaw.sip.common.transmit.request.SipRequestBuilderFactory;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService.Reason;
import io.github.lunasaw.voglander.client.service.cascade.CascadeCatalogRevokeService.RevokeResult;
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.SubType;
import io.github.lunasaw.voglander.common.event.LocalCascadeCatalogChangeEvent;
import io.github.lunasaw.voglander.common.event.LocalMobilePositionEvent;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeSubscribeDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import io.github.lunasaw.voglander.manager.manager.CascadeSubscribeManager;
import io.github.lunasaw.voglander.manager.manager.SysConfigManager;
import io.github.lunasaw.voglander.manager.sys.SysConfigCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联主动推送发布器（C3/C4/C5，出站推送单点收口）。
 *
 * <p>本地数据变更（通道上下线 / 告警 / 移动位置）时，遍历订阅了对应信息类型的上级，
 * 逐个主动 NOTIFY 推送。推送给上级的实体 deviceId 必须重映射为上级认识的
 * {@code cascade_channel_id}（不是本地 {@code local_channel_id}），重映射逻辑单点收口于本类。
 *
 * <p>dialog（V2 核验）：框架按 From/To 自管订阅 dialog，推送命令无需 callId 入参。
 *
 * @author luna
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sip.client.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CascadeNotifyPublisher {

    private final CascadeSubscribeManager cascadeSubscribeManager;
    private final CascadePlatformManager  cascadePlatformManager;
    private final CascadeChannelManager   cascadeChannelManager;
    private final CascadeDeviceSupplier   cascadeDeviceSupplier;
    private final CascadeNotifyAckTracker notifyAckTracker;
    private final CascadeCatalogAssembler catalogAssembler;
    /** 目录分片参数取值入口：与查询应答共用同一组库内覆盖值 */
    private final SysConfigManager        sysConfigManager;

    /** 单条目录 NOTIFY 容纳的通道数（yml 回落值），与 {@code CascadeQueryHandler} 共用同一配置项 */
    @Value("${gateway.cascade.catalog.batch-size:50}")
    private int catalogBatchSize = 50;

    /**
     * 组织取消同步（{@code REROUTE}）时，通道改挂域节点所采用的协议动作。
     *
     * <p>{@code UPDATE}：直接发 {@code Event=UPDATE} 并把 {@code ParentID} 改成域编码，
     * 报文数最少。{@code DEL_ADD}：先删旧节点再以新父节点新增，兼容不认同「UPDATE 改父」的上级。
     * 两种都依赖上级实现，切换成本为零，因此做成配置而非改代码。
     */
    @Value("${gateway.cascade.catalog.reparent-mode:UPDATE}")
    private String reparentMode = "UPDATE";

    /**
     * 删除前向上级取消上报时，等待其 2xx 的时长（秒）。
     *
     * <p>与 {@code gateway.cascade.notify.ack-timeout-sec} 分开配置：后者驱动后台重发，
     * 前者是用户点一次删除能容忍的等待上限，两者需要独立调。海康这类上级响应通常在 1s 内，
     * 10s 仍拿不到回应基本可以判定为链路不可用。
     */
    @Value("${gateway.cascade.catalog.delete-confirm-timeout-sec:10}")
    private int deleteConfirmTimeoutSec = 10;

    /**
     * 推送一次<b>完整目录快照</b>（GB28181 §9.11 Catalog NOTIFY）。
     *
     * <p><b>为什么必须有这一包</b>：上级（海康 iSecure Center 等）订阅目录后不会把 SUBSCRIBE 当目录来源，
     * 它只认紧随 200 OK 之后的第一条 Catalog NOTIFY 来建立目录基线；错过这一包，上级目录恒空——
     * 表现为「注册成功、心跳正常、但设备列表一直为空」。框架的 {@link ClientCommandSender} 只提供
     * 「目录应答(Response)」与「目录变更(Event)」两类命令，没有完整目录 NOTIFY，因此这里直接拼
     * MANSCDP XML 并以独立 Call-ID 发送（见 {@link #sendIsolated}）。
     *
     * <p><b>首包只含域节点</b>：目录是一棵树，上级要先拿到「本级平台」这个根节点，后面的组织/通道
     * 才有地方挂。海康侧对"根节点单独一包"的处理最稳（CSS 参考实现即如此），因此这里先发一包
     * {@code Num=1} 的域节点，再按 {@code gateway.cascade.catalog.batch-size} 分片下发其余节点。
     *
     * <p>SumNum 始终是<b>全部节点数</b>（域 1 + 组织 N + 通道 M），{@code DeviceList/@Num} 是本片条数。
     *
     * @param platformId 上级平台编码
     * @param sn         上级订阅/查询报文里的 SN（回包须原样带回，否则上级对不上号）
     * @return 实际发出的 MESSAGE 条数；上级平台配置不存在时返回 0
     */
    public int pushCatalogSnapshot(String platformId, String sn) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("目录快照推送跳过，找不到上级平台配置: platformId={}", platformId);
            return 0;
        }
        Endpoint ep = endpoint(platform);
        if (ep == null) {
            return 0;
        }
        /* 节点集合与查询应答同源，避免「查询看到的」和「推送看到的」长期漂移 */
        String domainId = catalogAssembler.resolveDomainId(platform, ep.from.getUserId());
        List<CascadeCatalogNode> nodes = catalogAssembler.assemble(platformId, domainId);

        int total = nodes.size();
        int batchSize = batchSize();
        String snValue = StringUtils.defaultIfBlank(sn, String.valueOf(System.currentTimeMillis() % 1_000_000));
        String eventId = resolveCatalogEventId(platformId, snValue);

        int sent = 0;
        try {
            /* 第一包只放域节点：上级据此建立根节点，后续分片才有父可挂 */
            sendIsolated(ep, catalogAssembler.renderNotify(domainId, snValue, total, nodes.subList(0, 1)), eventId);
            sent++;
            for (int offset = 1; offset < total; offset += batchSize) {
                List<CascadeCatalogNode> batch = nodes.subList(offset, Math.min(offset + batchSize, total));
                sendIsolated(ep, catalogAssembler.renderNotify(domainId, snValue, total, batch), eventId);
                sent++;
            }
        } catch (Exception e) {
            /* 已发出的分片不回滚：上级按 SN 拼装，重订阅时会再推一次完整快照 */
            log.error("目录快照推送失败: platformId={}, sn={}, 已发 {} 包", platformId, snValue, sent, e);
        }
        log.info("目录快照推送完成: platformId={}, sn={}, eventId={}, 节点数={}, 分包={}, 批次大小={}",
            platformId, snValue, eventId, total, sent, batchSize);
        return sent;
    }

    /**
     * 生效的分片大小：库内覆盖值优先，取不到或非法时回落 yml/默认值。
     *
     * @return 恒为正数
     */
    private int batchSize() {
        int configured = sysConfigManager.getInt(SysConfigCatalog.CASCADE_CATALOG_BATCH_SIZE, catalogBatchSize);
        return configured > 0 ? configured : 50;
    }

    /**
     * 订阅通知的 {@code Event: Catalog;id=<值>}：必须用上级 SUBSCRIBE 里的订阅 id（即订阅 SN）。
     *
     * <p>优先取订阅表里该平台生效中的 CATALOG 订阅；取不到时回落本次推送的 SN。
     */
    private String resolveCatalogEventId(String platformId, String fallback) {
        List<CascadeSubscribeDTO> subs = cascadeSubscribeManager.listActiveByType(SubType.CATALOG);
        if (subs != null) {
            for (CascadeSubscribeDTO sub : subs) {
                if (platformId.equals(sub.getPlatformId()) && StringUtils.isNotBlank(sub.getSn())) {
                    return sub.getSn();
                }
            }
        }
        return fallback;
    }

    /**
     * 以<b>独立 SIP 事务</b>发一条 Catalog NOTIFY。
     *
     * <p><b>为什么必须显式指定 Call-ID</b>：{@link SipSender#request} 的 Call-ID 取值优先级是
     * 「ThreadLocal 事务上下文 &gt; ToDevice.callId &gt; 新生成」，而本类的推送常被
     * {@code CascadeSubscribeRequestProcessor} / {@code CascadeClientRegisterListener} 在
     * <b>SIP 请求处理线程</b>中同步调用，此时 ThreadLocal 里存着上级 SUBSCRIBE/REGISTER 的 Call-ID。
     * 若直接 {@code SipSender.doMessageRequest}，NOTIFY 会带着上级自己的 Call-ID 发出去，
     * 上级判定为非法请求并回 {@code 400 Bad Request}——表现为「推送有发出、上级目录仍空」。
     *
     * <p><b>为什么自己构造 Request</b>：框架的 {@code SipSender.request(...).subscribeInfo(...)}
     * 只在 SUBSCRIBE 分支生效，NOTIFY 不会写 {@code Event} / {@code Subscription-State} 头，
     * 而海康 NcgV5 对缺这两个头的 Catalog NOTIFY 一律回 400。
     *
     * <p>注意：对上级 Query 的<b>应答</b>（{@code CascadeQueryHandler}）必须沿用入站 Call-ID，
     * 不能走本方法。
     */
    private String sendIsolated(Endpoint ep, String content, String eventId) throws ParseException {
        String callId = SipRequestUtils.getNewCallId();
        Request request = SipRequestBuilderFactory.createNotifyRequest(ep.from, ep.to, content, callId);
        /* 按平台 charset 重写报文字节，与报文头声明保持一致（否则上级按声明解码，中文名全乱码） */
        request.setContent(CascadeManscdpXml.encode(content, ep.charset),
            (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME));
        request.addHeader(SipRequestUtils.createEventHeader("Catalog", eventId));
        request.addHeader(SipRequestUtils.createSubscriptionStateHeader("active"));
        SipSender.transmitRequest(ep.from.getIp(), request);
        return callId;
    }

    /**
     * 目录变更 → 推送所有订阅了 Catalog 的上级。
     *
     * <p>目录变更以"送达确认为准"：一次推送丢失会让上级目录与本地长期不一致，
     * 因此这里登记到 {@link CascadeNotifyAckTracker}，超时未确认自动重发一次。
     *
     * @param event ON/OFF/ADD/DEL/UPDATE
     */
    public void pushCatalogChange(String localDeviceId, String localChannelId, String event) {
        for (CascadeSubscribeDTO sub : cascadeSubscribeManager.listActiveByType(SubType.CATALOG)) {
            CascadeChannelDTO ch = cascadeChannelManager.getByPlatformAndChannel(sub.getPlatformId(), localChannelId);
            if (ch == null) {
                continue; // 该上级未暴露此通道
            }
            Endpoint ep = endpoint(sub.getPlatformId());
            if (ep == null) {
                continue;
            }
            DeviceOtherUpdateNotify.OtherItem item = new DeviceOtherUpdateNotify.OtherItem();
            item.setDeviceId(ch.getCascadeChannelId()); // 重映射为上级编码
            item.setEvent(event);
            String callId = ClientCommandSender.sendCatalogChangeNotify(ep.from, ep.to, sub.getSn(), List.of(item));
            notifyAckTracker.track(callId, sub.getPlatformId(),
                "目录变更 " + event + " → " + ch.getCascadeChannelId(),
                () -> ClientCommandSender.sendCatalogChangeNotify(ep.from, ep.to, sub.getSn(), List.of(item)));
            log.info("推送目录变更: platformId={}, cascadeChannelId={}, event={}, callId={}",
                sub.getPlatformId(), ch.getCascadeChannelId(), event, callId);
        }
    }

    /**
     * 目录节点被删除 → 向所有订阅了 Catalog 的上级发 {@code Event=DEL} 通知。
     *
     * <p><b>为什么不能只重推快照</b>：完整目录快照对上级是增量 upsert 语义，海康收到「少了一个节点」
     * 的完整快照只会更新出现过的节点，不会删除上次同步过、这次没出现的节点——实测两包均 200 OK，
     * 目录里旧组织仍在。删除必须显式给 {@code <Event>DEL</Event>}。
     *
     * <p><b>不查库</b>：删除场景下记录已经落库删掉了，按编码直发才不会漏发（{@link #pushCatalogChange}
     * 需要先查到通道，DEL 时查不到就直接跳过了）。
     *
     * @param platformId 上级平台编码
     * @param cascadeId  被删节点的上级编码（组织编码 / 级联通道编码）
     */
    public void pushCatalogDelete(String platformId, String cascadeId) {
        pushCatalogDeleteBatch(platformId,
            List.of(new LocalCascadeCatalogChangeEvent.Node(cascadeId, null, null)));
    }

    /**
     * 批量删除目录节点 → 向上级逐条发 {@code Event=DEL}。
     *
     * <p><b>为什么逐条发而不是攒成一个包</b>：参考实现（CSS {@code notifyOrgDeltaData}）就是
     * 一条一个 {@code SumNum=1} 的 NOTIFY；海康对「一包多 Item 的删除」没有实测结论，
     * 逐条发至少能保证单个节点失败不影响其余，报文也能带上各自的 {@code Name}/{@code ParentID}。
     *
     * <p><b>调用方必须保证清单已按「叶子 → 根」后序排好</b>：先删父会让子节点变成孤儿，
     * 部分上级直接忽略对该子节点的后续删除。
     *
     * @param nodes 待删除节点，已排好序；空清单直接返回
     * @return 实际发出的报文条数
     */
    public int pushCatalogDeleteBatch(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        if (StringUtils.isBlank(platformId) || nodes == null || nodes.isEmpty()) {
            return 0;
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("目录删除通知跳过，找不到上级平台配置: platformId={}", platformId);
            return 0;
        }
        Endpoint ep = endpoint(platform);
        if (ep == null) {
            return 0;
        }
        String domainId = catalogAssembler.resolveDomainId(platform, ep.from.getUserId());
        String eventId = resolveCatalogEventId(platformId, nextSn());
        int sent = 0;
        for (LocalCascadeCatalogChangeEvent.Node node : nodes) {
            if (node == null || StringUtils.isBlank(node.cascadeId())) {
                continue;
            }
            try {
                String callId = sendIsolated(ep, CascadeManscdpXml.catalogEventNotify(domainId, nextSn(),
                    new CascadeManscdpXml.CatalogEventItem(node.cascadeId(), node.name(),
                        /* 根组织没有父组织编码，落到域节点才能让上级定位到树 */
                        StringUtils.defaultIfBlank(node.parentCode(), domainId), "DEL")),
                    eventId);
                sent++;
                log.info("推送目录删除通知: platformId={}, cascadeId={}, callId={}",
                    platformId, node.cascadeId(), callId);
            } catch (Exception e) {
                /* 单条失败不打断整批：后续节点仍要删，否则上级目录会残留半棵子树 */
                log.error("推送目录删除通知失败: platformId={}, cascadeId={}",
                    platformId, node.cascadeId(), e);
            }
        }
        return sent;
    }

    /**
     * 向上级<b>同步</b>撤销目录节点：逐条发 {@code Event=DEL} 并等上级 2xx。
     *
     * <p><b>与 {@link #pushCatalogDeleteBatch} 的分工</b>：那个方法只统计"发出了几条"，
     * 不等结论，服务于不要求确认的被动场景（设备自己上报了 Catalog DEL）；删除本地资产前
     * 必须知道上级到底取消没取消，所以这里额外 {@code trackSync} 并等满确认窗口。
     *
     * <p>报文全部先发出去再统一等响应：N 个节点的总耗时接近一次往返，而不是 N 次串行的往返。
     *
     * <p><b>不抛异常</b>：结论用 {@link RevokeResult} 返回，拦不拦由 manager 层的业务规则决定，
     * 协议适配层不参与业务判断。
     *
     * @param platformId 上级平台编码
     * @param nodes      待撤销节点，必须已按「叶子 → 根」排序
     * @return 撤销结论，永不返回 {@code null}
     */
    public RevokeResult revokeCatalogNodes(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        if (StringUtils.isBlank(platformId) || nodes == null || nodes.isEmpty()) {
            return new RevokeResult(platformId, Reason.CONFIRMED, 0, 0);
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("目录撤销跳过，找不到上级平台配置: platformId={}, 节点数={}", platformId, nodes.size());
            return new RevokeResult(platformId, Reason.PLATFORM_ABSENT, 0, nodes.size());
        }
        Endpoint ep = endpoint(platform);
        if (ep == null) {
            return new RevokeResult(platformId, Reason.PLATFORM_ABSENT, 0, nodes.size());
        }
        String domainId = catalogAssembler.resolveDomainId(platform, ep.from.getUserId());
        String eventId = resolveCatalogEventId(platformId, nextSn());

        List<String> callIds = new ArrayList<>(nodes.size());
        for (LocalCascadeCatalogChangeEvent.Node node : nodes) {
            if (node == null || StringUtils.isBlank(node.cascadeId())) {
                continue;
            }
            try {
                String callId = sendIsolated(ep, CascadeManscdpXml.catalogEventNotify(domainId, nextSn(),
                    new CascadeManscdpXml.CatalogEventItem(node.cascadeId(), node.name(),
                        StringUtils.defaultIfBlank(node.parentCode(), domainId), "DEL")),
                    eventId);
                notifyAckTracker.trackSync(callId, platformId, "目录撤销 DEL → " + node.cascadeId());
                callIds.add(callId);
            } catch (Exception e) {
                /* 发不出去就已有结论：不必再等，剩余节点也不会被上级看到这次删除 */
                log.error("目录撤销报文发送失败: platformId={}, cascadeId={}", platformId, node.cascadeId(), e);
                notifyAckTracker.awaitDelivery(callIds, 0L);
                return new RevokeResult(platformId, Reason.SEND_FAILED, 0, callIds.size());
            }
        }
        if (callIds.isEmpty()) {
            return new RevokeResult(platformId, Reason.CONFIRMED, 0, 0);
        }

        Map<String, Integer> statuses = notifyAckTracker.awaitDelivery(callIds,
            Math.max(1, deleteConfirmTimeoutSec) * 1000L);
        int refusedStatus = 0;
        int timedOut = 0;
        for (Integer status : statuses.values()) {
            if (status == CascadeNotifyAckTracker.STATUS_NO_RESPONSE) {
                timedOut++;
            } else if (status < 200 || status >= 300) {
                refusedStatus = status;
            }
        }
        Reason reason = refusedStatus > 0 ? Reason.REFUSED : (timedOut > 0 ? Reason.TIMEOUT : Reason.CONFIRMED);
        if (reason != Reason.CONFIRMED) {
            log.warn("目录撤销未获上级确认: platformId={}, reason={}, status={}, 节点数={}, 未响应数={}",
                platformId, reason, refusedStatus, callIds.size(), timedOut);
        } else {
            log.info("目录撤销已获上级确认: platformId={}, 节点数={}", platformId, callIds.size());
        }
        return new RevokeResult(platformId, reason, refusedStatus, callIds.size());
    }

    /**
     * 通道改挂域节点（组织「取消同步」选 {@code REROUTE} 时用）。
     *
     * <p>上级没有「移动节点」语义，{@code ParentID} 变更只能靠更新父指针表达。
     * 动作由 {@code gateway.cascade.catalog.reparent-mode} 决定：
     * {@code UPDATE} 一包搞定；{@code DEL_ADD} 先删旧父下的节点再以域节点为父新增。
     *
     * @param nodes 通道节点；{@code parentCode} 为原父组织编码（{@code DEL_ADD} 模式需要）
     * @return 实际发出的报文条数
     */
    public int pushCatalogReparent(String platformId, List<LocalCascadeCatalogChangeEvent.Node> nodes) {
        if (StringUtils.isBlank(platformId) || nodes == null || nodes.isEmpty()) {
            return 0;
        }
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("目录改挂通知跳过，找不到上级平台配置: platformId={}", platformId);
            return 0;
        }
        Endpoint ep = endpoint(platform);
        if (ep == null) {
            return 0;
        }
        String domainId = catalogAssembler.resolveDomainId(platform, ep.from.getUserId());
        String eventId = resolveCatalogEventId(platformId, nextSn());
        boolean delAdd = "DEL_ADD".equalsIgnoreCase(reparentMode);
        int sent = 0;
        for (LocalCascadeCatalogChangeEvent.Node node : nodes) {
            if (node == null || StringUtils.isBlank(node.cascadeId())) {
                continue;
            }
            try {
                if (delAdd) {
                    sendIsolated(ep, CascadeManscdpXml.catalogEventNotify(domainId, nextSn(),
                        new CascadeManscdpXml.CatalogEventItem(node.cascadeId(), node.name(), node.parentCode(), "DEL")),
                        eventId);
                    sendIsolated(ep, CascadeManscdpXml.catalogEventNotify(domainId, nextSn(),
                        new CascadeManscdpXml.CatalogEventItem(node.cascadeId(), node.name(), domainId, "ADD")),
                        eventId);
                    sent += 2;
                } else {
                    sendIsolated(ep, CascadeManscdpXml.catalogEventNotify(domainId, nextSn(),
                        new CascadeManscdpXml.CatalogEventItem(node.cascadeId(), node.name(), domainId, "UPDATE")),
                        eventId);
                    sent++;
                }
                log.info("推送目录改挂通知: platformId={}, cascadeId={}, mode={}",
                    platformId, node.cascadeId(), delAdd ? "DEL_ADD" : "UPDATE");
            } catch (Exception e) {
                log.error("推送目录改挂通知失败: platformId={}, cascadeId={}", platformId, node.cascadeId(), e);
            }
        }
        return sent;
    }

    /** 目录变更通知的 SN：与快照一致用时间戳取模，保证单次调用内不重复且不长于 6 位 */
    private String nextSn() {
        return String.valueOf(System.currentTimeMillis() % 1_000_000);
    }

    /** 告警 → 推送所有订阅了 Alarm 的上级。 */
    public void pushAlarm(String localDeviceId, String localChannelId, String alarmType,
        String alarmPriority, String alarmTime) {
        for (CascadeSubscribeDTO sub : cascadeSubscribeManager.listActiveByType(SubType.ALARM)) {
            CascadeChannelDTO ch = cascadeChannelManager.getByPlatformAndChannel(sub.getPlatformId(), localChannelId);
            // 通道级告警须找到映射；设备级告警(channelId 为空)用平台 localClientId 兜底
            String cascadeDeviceId = ch != null ? ch.getCascadeChannelId() : null;
            if (cascadeDeviceId == null && localChannelId != null) {
                continue; // 指明了通道但该上级未暴露 → 跳过
            }
            Endpoint ep = endpoint(sub.getPlatformId());
            if (ep == null) {
                continue;
            }
            String targetDeviceId = cascadeDeviceId != null ? cascadeDeviceId : ep.to.getUserId();
            DeviceAlarmNotify notify = new DeviceAlarmNotify();
            notify.setCmdType(CmdTypeEnum.ALARM.getType());
            notify.setSn(sub.getSn());
            notify.setDeviceId(targetDeviceId); // 重映射为上级编码
            notify.setAlarmPriority(alarmPriority);
            notify.setAlarmTime(alarmTime);
            ClientCommandSender.sendAlarmNotify(ep.from, ep.to, notify);
            log.info("推送告警: platformId={}, deviceId={}, type={}", sub.getPlatformId(), targetDeviceId, alarmType);
        }
    }

    /** 移动位置 → 推送所有订阅了 MobilePosition 的上级。 */
    public void pushMobilePosition(LocalMobilePositionEvent e) {
        for (CascadeSubscribeDTO sub : cascadeSubscribeManager.listActiveByType(SubType.MOBILE_POSITION)) {
            CascadeChannelDTO ch = cascadeChannelManager.getByPlatformAndChannel(sub.getPlatformId(), e.getChannelId());
            String cascadeDeviceId = ch != null ? ch.getCascadeChannelId() : null;
            if (cascadeDeviceId == null && e.getChannelId() != null) {
                continue;
            }
            Endpoint ep = endpoint(sub.getPlatformId());
            if (ep == null) {
                continue;
            }
            MobilePositionNotify notify = new MobilePositionNotify();
            notify.setDeviceId(cascadeDeviceId != null ? cascadeDeviceId : ep.to.getUserId());
            notify.setTime(e.getTime());
            notify.setLongitude(parseDouble(e.getLongitude()));
            notify.setLatitude(parseDouble(e.getLatitude()));
            notify.setSpeed(parseDouble(e.getSpeed()));
            notify.setDirection(parseDouble(e.getDirection()));
            notify.setAltitude(parseDouble(e.getAltitude()));
            ClientCommandSender.sendMobilePositionNotify(ep.from, ep.to, notify);
            log.info("推送移动位置: platformId={}, deviceId={}", sub.getPlatformId(), notify.getDeviceId());
        }
    }

    /**
     * 构造某上级的 From/To 端点；平台不存在则 null。
     */
    private Endpoint endpoint(String platformId) {
        CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
        if (platform == null) {
            log.warn("级联推送找不到上级平台配置: platformId={}", platformId);
            return null;
        }
        return endpoint(platform);
    }

    /** 构造端点：From 用平台身份（localClientId），To 用上级身份；同时带上推送用的字符集。 */
    private Endpoint endpoint(CascadePlatformDTO platform) {
        FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
        ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
        return new Endpoint(from, to, platform.getCharset());
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Endpoint {
        final FromDevice from;
        final ToDevice   to;
        /** 上级平台字符集（{@code tb_cascade_platform.charset}），决定报文实际字节与头部声明 */
        final String     charset;

        Endpoint(FromDevice from, ToDevice to, String charset) {
            this.from = from;
            this.to = to;
            this.charset = charset;
        }
    }
}
