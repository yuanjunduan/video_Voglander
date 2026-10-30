package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.util.List;

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
import io.github.lunasaw.voglander.common.constant.cascade.CascadeConstant.SubType;
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
    private String sendIsolated(Endpoint ep, String content, String eventId) {
        String callId = SipRequestUtils.getNewCallId();
        Request request = SipRequestBuilderFactory.createNotifyRequest(ep.from, ep.to, content, callId);
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

    /** 构造端点：From 用平台身份（localClientId），To 用上级身份。 */
    private Endpoint endpoint(CascadePlatformDTO platform) {
        FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
        ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
        return new Endpoint(from, to);
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

        Endpoint(FromDevice from, ToDevice to) {
            this.from = from;
            this.to = to;
        }
    }
}
