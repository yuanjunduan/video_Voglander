package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import io.github.lunasaw.gb28181.common.entity.enums.InviteSessionNameEnum;
import io.github.lunasaw.gb28181.common.entity.mansrtsp.ManSrtspRequest;
import io.github.lunasaw.gb28181.common.entity.sdp.GbSessionDescription;
import io.github.lunasaw.gbproxy.client.entity.InviteResponseEntity;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientAckEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientByeEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientInfoEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientInviteEvent;
import io.github.lunasaw.gbproxy.server.enums.PlayActionEnums;
import io.github.lunasaw.sip.common.enums.ContentTypeEnum;
import io.github.lunasaw.sip.common.transmit.ResponseCmd;
import io.github.lunasaw.sip.common.transmit.SipTransactionRegistry;
import io.github.lunasaw.voglander.intergration.wrapper.gb28181.server.command.media.VoglanderServerMediaCommand;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.config.ZlmIntegrationConfig;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadeChannelDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import io.github.lunasaw.zlm.api.ZlmRestService;
import io.github.lunasaw.zlm.entity.rtp.CloseSendRtpReq;
import io.github.lunasaw.zlm.entity.rtp.StartSendRtpReq;
import io.github.lunasaw.zlm.entity.rtp.StartSendRtpResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sip.message.Response;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage 4 — 响应上级平台的 INVITE/BYE/INFO，完成双向媒体转发（实时点播 + 回放点播 + 回放控制）。
 *
 * <p>流程：上级 INVITE → 向真实 IPC 拉流（实时 inviteRealTimePlay / 回放 invitePlayBack）
 * → 分配本端 RTP 发送端口并回 200 OK（带本端发流 SDP，框架
 * {@link InviteResponseEntity#getAckPlayBody}/{@code getAckPlayBackBody} 构造，sendonly）
 * → 上级 ACK 到达后才调用 ZLM startSendRtp 向上级推流。
 * 上级 BYE → stopSendRtp；上级 INFO（回放控制 PLAY/PAUSE/SCALE/拖动）→ 透传真实设备
 * {@link VoglanderServerMediaCommand#controlPlayBack}。
 *
 * @author luna
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sip.client.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CascadeMediaInviteListener {

    private final CascadeChannelManager       cascadeChannelManager;
    private final VoglanderServerMediaCommand serverMediaCommand;

    @Autowired(required = false)
    private ZlmIntegrationConfig              zlmConfig;

    /** callId → 已应答但尚未收到 ACK 的会话（此时还没有推流）。 */
    private final ConcurrentHashMap<String, MediaSession> pendingSessions = new ConcurrentHashMap<>();

    /** callId → 媒体会话上下文（ssrc + 真实设备 + 上级 userId），用于 BYE 停推流；INFO 按 userId 反查。 */
    private final ConcurrentHashMap<String, MediaSession> activeSessions = new ConcurrentHashMap<>();

    /** 本端 RTP 发送端口分配游标（在 [sendPortStart, sendPortEnd] 内轮转） */
    private final AtomicInteger nextSendPort = new AtomicInteger();

    /** 本端发送端口范围：200 OK 里通告的 m= 端口必须与 startSendRtp 的 src_port 一致 */
    @Value("${gateway.cascade.media.send-port-start:30000}")
    private int  sendPortStart = 30000;

    @Value("${gateway.cascade.media.send-port-end:30999}")
    private int  sendPortEnd = 30999;

    /** 等待上级 ACK 的最长时间（秒）：超时说明对话未建立，不能推流 */
    @Value("${gateway.cascade.media.ack-timeout-sec:32}")
    private long ackTimeoutSec = 32;

    /**
     * 乐观兜底推流延迟（毫秒）：ACK 是 SIP 请求，部分 SIP 栈不会把 2xx 对话的 ACK 回调给监听器，
     * 若严格等 ACK 会导致上级永久黑屏。设为 0 或负数可关闭兜底（只认 ACK）。
     */
    @Value("${gateway.cascade.media.optimistic-push-delay-ms:2000}")
    private long optimisticPushDelayMs = 2000L;

    @EventListener
    @Async("sipNotifierExecutor")
    public void onInvite(ClientInviteEvent event) {
        String callId = event.getCallId();
        String userId = event.getUserId();
        try {
            GbSessionDescription sdp = (GbSessionDescription) event.getSessionDescription();
            if (sdp == null) {
                log.warn("级联 INVITE SDP 为空: callId={}", callId);
                sendInviteError(event);
                return;
            }

            String cascadeChannelId = parseCascadeChannelId(sdp);
            String platformId = parsePlatformId(sdp, userId);

            // 0. 判断：是否是级联通道（查询数据库）
            CascadeChannelDTO channel = cascadeChannelManager.getByPlatformAndCascadeChannelId(platformId, cascadeChannelId);
            if (channel == null) {
                log.debug("级联 INVITE过滤: platformId={}, cascadeChannelId={} 不是级联通道", platformId, cascadeChannelId);
                return; // 不是级联通道，不处理（可能是Lab设备）
            }

            boolean playback = isPlayback(sdp);
            /* 1. 向真实 IPC 拉流（实时 / 回放分支） */
            ensureStreamOnline(channel, sdp, playback);

            /* 2. 只做准备：确定推流目标与本地发送端口，startSendRtp 推迟到 ACK 之后 */
            String ssrc = sdp.getSsrc() != null ? sdp.getSsrc() : "0";
            if (zlmConfig == null) {
                log.error("无 ZLM 配置: callId={}", callId);
                sendInviteError(event);
                return;
            }
            ZlmIntegrationConfig.ZlmServerConfig node = zlmConfig.getDefaultServer();
            if (node == null) {
                log.error("无可用 ZLM 节点: callId={}", callId);
                sendInviteError(event);
                return;
            }
            String zlmApp    = "rtp";
            String zlmStream = channel.getLocalDeviceId() + "_" + channel.getLocalChannelId();
            int    srcPort   = allocateSendPort();
            int    dstPort   = sdp.getPort() != null ? sdp.getPort() : 10000;
            boolean udp      = sdp.getTransport() == null || !sdp.getTransport().name().contains("TCP");
            pendingSessions.put(callId, new MediaSession(ssrc, channel.getLocalDeviceId(), userId, playback,
                zlmApp, zlmStream, sdp.getAddress(), dstPort, udp, srcPort));

            /* 3. 回包 200 OK：SDP 通告的端口即 ACK 后 startSendRtp 使用的 src_port */
            String localSendIp = hostOf(node.getHostPort());
            sendInviteOk(event, userId, localSendIp, String.valueOf(srcPort), ssrc, sdp);
            log.info("级联 INVITE 已应答，等待 ACK 后推流: callId={}, cascadeChannelId={}, playback={}, srcPort={}",
                callId, cascadeChannelId, playback, srcPort);

        } catch (Exception e) {
            log.error("处理级联 INVITE 失败: callId={}", callId, e);
            sendInviteError(event);
        }
    }

    /**
     * 上级 ACK 到达：此时对话已建立，才真正调用 ZLM 推流。
     *
     * <p>这是与参考实现 CSS 对齐的关键点：INVITE 立即推流会在上级不回 ACK 时产生无效推流，
     * 既浪费端口又会与后续同 ssrc 的会话冲突。未收到 ACK 的会话由 {@link #cleanExpiredPending()} 回收。
     */
    @EventListener
    @Async("sipNotifierExecutor")
    public void onAck(ClientAckEvent event) {
        String       callId  = event.getCallId();
        MediaSession session = pendingSessions.remove(callId);
        if (session == null) {
            log.debug("收到非级联或已处理的 ACK: callId={}", callId);
            return;
        }
        startPush(callId, session);
    }

    /**
     * 真正调用 ZLM 开始向上级推流。
     *
     * <p>幂等：同一 callId 已在 {@link #activeSessions} 中则直接返回，ACK 与兜底扫描可能同时触发。
     *
     * @return 是否由本次调用成功启动推流
     */
    private boolean startPush(String callId, MediaSession session) {
        if (activeSessions.containsKey(callId)) {
            log.debug("推流已存在，跳过重复启动: callId={}", callId);
            return false;
        }
        if (zlmConfig == null) {
            log.error("无 ZLM 配置，放弃推流: callId={}", callId);
            return false;
        }
        ZlmIntegrationConfig.ZlmServerConfig node = zlmConfig.getDefaultServer();
        if (node == null) {
            log.error("无可用 ZLM 节点，放弃推流: callId={}", callId);
            return false;
        }
        try {
            StartSendRtpReq req = buildStartSendRtpReq(session);
            StartSendRtpResult result = ZlmRestService.startSendRtp(node.getHostPort(), node.getSecret(), req);
            if (result == null || !"0".equals(result.getCode())) {
                log.error("ZLM startSendRtp 失败: callId={}, srcPort={}, result={}", callId, session.srcPort, result);
                return false;
            }
            activeSessions.put(callId, session);
            log.info("级联推流已启动: callId={}, ssrc={}, srcPort={} -> {}:{}, udp={}",
                callId, session.ssrc, session.srcPort, session.dstUrl, session.dstPort, session.udp);
            return true;
        } catch (Exception e) {
            log.error("启动级联推流失败: callId={}", callId, e);
            return false;
        }
    }

    /**
     * 扫描尚未推流的会话，处理两档阈值：
     * <ol>
     *   <li>乐观兜底（{@code optimistic-push-delay-ms}，默认 2s）：ACK 迟迟未被观测到时先推流，
     *       避免 SIP 栈未回调 ACK 导致上级彻底黑屏；ACK 之后仍会走 {@link #startPush}，但已是幂等空操作。</li>
     *   <li>超时放弃（{@code ack-timeout-sec}，默认 32s）：INVITE 事务已超时，上级不会再来收流，回收端口。</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${gateway.cascade.media.ack-scan-interval-ms:1000}")
    public void cleanExpiredPending() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MediaSession> entry : pendingSessions.entrySet()) {
            String       callId  = entry.getKey();
            MediaSession session = entry.getValue();
            long         age     = now - session.createdAt;

            /* 1. 乐观兜底：到点仍未见 ACK，先推流（同一会话可能已被 ACK 抢先摘除，用 remove(k,v) 原子判断） */
            if (optimisticPushDelayMs > 0 && age >= optimisticPushDelayMs) {
                if (!pendingSessions.remove(callId, session)) {
                    continue;
                }
                log.warn("级联 INVITE 在 {}ms 内未观测到 ACK，启用兜底推流: callId={}, srcPort={}",
                    age, callId, session.srcPort);
                startPush(callId, session);
                continue;
            }

            /* 2. 超时放弃：等待期间端口一直被占用，必须回收 */
            if (age >= ackTimeoutSec * 1000L) {
                if (pendingSessions.remove(callId, session)) {
                    log.warn("级联 INVITE 未收到 ACK，放弃推流: callId={}, srcPort={}, 等待 {}s",
                        callId, session.srcPort, ackTimeoutSec);
                }
            }
        }
    }

    @EventListener
    @Async("sipNotifierExecutor")
    public void onBye(ClientByeEvent event) {
        String callId = event.getCallId();
        /* 尚未收到 ACK 就 BYE：会话从未推流，只需回收端口占用 */
        pendingSessions.remove(callId);
        MediaSession session = activeSessions.remove(callId);
        if (session == null) {
            return;
        }
        try {
            if (zlmConfig != null) {
                ZlmIntegrationConfig.ZlmServerConfig node = zlmConfig.getDefaultServer();
                if (node != null) {
                    CloseSendRtpReq req = new CloseSendRtpReq();
                    req.setSsrc(session.ssrc);
                    ZlmRestService.stopSendRtp(node.getHostPort(), node.getSecret(), req);
                }
            }
            log.info("级联 BYE 处理成功: callId={}", callId);
        } catch (Exception e) {
            log.error("处理级联 BYE 失败: callId={}", callId, e);
        }
    }

    /**
     * 上级在回放会话内发 INFO（RTSP 风格 PLAY/PAUSE/SCALE/拖动）→ 透传真实设备。
     * <p>
     * {@link ClientInfoEvent#getUserId()} 为 To 头 userId（上级寻址的级联通道编码），按此反查活跃回放会话取真实设备。
     */
    @EventListener
    @Async("sipNotifierExecutor")
    public void onInfo(ClientInfoEvent event) {
        String userId = event.getUserId();
        ManSrtspRequest rtsp = event.getParsed();
        if (rtsp == null) {
            log.debug("INFO 无 MANSRTSP 解析体，忽略: userId={}", userId);
            return;
        }
        MediaSession session = findPlaybackSessionByUserId(userId);
        if (session == null) {
            log.warn("回放控制找不到活跃会话: userId={}", userId);
            return;
        }
        String localDeviceId = session.localDeviceId;
        String method = rtsp.getMethod() == null ? "" : rtsp.getMethod().toUpperCase();
        try {
            switch (method) {
                case "PAUSE":
                    serverMediaCommand.controlPlayBack(localDeviceId, PlayActionEnums.PLAY_RESUME);
                    log.info("回放控制透传: PAUSE → localDevice={}", localDeviceId);
                    break;
                case "PLAY":
                    if (rtsp.getScale() != null) {
                        serverMediaCommand.controlPlayBack(localDeviceId, PlayActionEnums.PLAY_SPEED, rtsp.getScale());
                        log.info("回放控制透传: SCALE={} → localDevice={}", rtsp.getScale(), localDeviceId);
                    } else if (StringUtils.isNotBlank(rtsp.getRange())) {
                        Long seek = parseRangeSeconds(rtsp.getRange());
                        if (seek != null) {
                            serverMediaCommand.controlPlayBack(localDeviceId, PlayActionEnums.PLAY_RANGE, seek);
                            log.info("回放控制透传: SEEK={}s → localDevice={}", seek, localDeviceId);
                        } else {
                            serverMediaCommand.controlPlayBack(localDeviceId, PlayActionEnums.PLAY_NOW);
                            log.info("回放控制透传: PLAY(Range 无法解析,降级恢复) → localDevice={}", localDeviceId);
                        }
                    } else {
                        serverMediaCommand.controlPlayBack(localDeviceId, PlayActionEnums.PLAY_NOW);
                        log.info("回放控制透传: PLAY(恢复) → localDevice={}", localDeviceId);
                    }
                    break;
                default:
                    log.warn("回放控制未知 RTSP 方法: method={}, userId={}", method, userId);
            }
        } catch (Exception e) {
            log.error("回放控制透传失败: userId={}, method={}", userId, method, e);
        }
    }

    private MediaSession findPlaybackSessionByUserId(String userId) {
        if (userId == null) {
            return null;
        }
        return activeSessions.values().stream()
            .filter(s -> s.playback && userId.equals(s.upperUserId))
            .findFirst()
            .orElse(null);
    }

    /**
     * 解析 RTSP Range 头（{@code npt=10-} / {@code npt=10.5-30}）的起始秒数。
     */
    public static Long parseRangeSeconds(String range) {
        if (StringUtils.isBlank(range)) {
            return null;
        }
        try {
            String v = range.trim();
            int eq = v.indexOf('=');
            if (eq >= 0) {
                v = v.substring(eq + 1);
            }
            int dash = v.indexOf('-');
            if (dash >= 0) {
                v = v.substring(0, dash);
            }
            v = v.trim();
            if (v.isEmpty()) {
                return null;
            }
            return (long) Double.parseDouble(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** 触发向真实设备的拉流（实时 / 回放）。 */
    private void ensureStreamOnline(CascadeChannelDTO channel, GbSessionDescription sdp, boolean playback) {
        String sdpIp = sdp.getAddress();
        int port = sdp.getPort() != null ? sdp.getPort() : 10000;
        if (playback) {
            String[] window = parsePlaybackWindow(sdp);
            serverMediaCommand.invitePlayBack(channel.getLocalDeviceId(), sdpIp, port, window[0], window[1]);
        } else {
            serverMediaCommand.inviteRealTimePlay(channel.getLocalDeviceId(), sdpIp, port);
        }
    }

    private StartSendRtpReq buildStartSendRtpReq(MediaSession session) {
        StartSendRtpReq req = new StartSendRtpReq();
        req.setApp(session.app);
        req.setStream(session.stream);
        req.setDstUrl(session.dstUrl);
        req.setDstPort(session.dstPort);
        req.setSsrc(parseSsrc(session.ssrc));
        req.setUdp(session.udp);
        req.setUsePs(1);
        /* src_port 必须等于 200 OK SDP 里通告的 m= 端口，否则上级按 SDP 收流会收不到 */
        req.setSrcPort(session.srcPort);
        return req;
    }

    /**
     * 分配本端 RTP 发送端口：在 [sendPortStart, sendPortEnd] 内轮转，跳过已被待推流/已推流会话占用的端口。
     *
     * <p>该端口会写进 200 OK 的 SDP 通告给上级，同一端口被两个会话复用会导致串流与花屏，
     * 因此必须排他。端口耗尽时退回起点并告警——此时说明存在会话泄漏，需要排查 BYE 是否丢失。
     */
    private int allocateSendPort() {
        int span = Math.max(1, sendPortEnd - sendPortStart + 1);
        for (int i = 0; i < span; i++) {
            int candidate = sendPortStart + Math.floorMod(nextSendPort.getAndIncrement(), span);
            if (!isPortInUse(candidate)) {
                return candidate;
            }
        }
        log.warn("级联发流端口已耗尽，复用起始端口: [{}, {}]，请检查是否存在未回收的会话", sendPortStart, sendPortEnd);
        return sendPortStart;
    }

    private boolean isPortInUse(int port) {
        return pendingSessions.values().stream().anyMatch(s -> s.srcPort == port)
            || activeSessions.values().stream().anyMatch(s -> s.srcPort == port);
    }

    /**
     * 回 200 OK 带本端发流 SDP（sendonly）。实时用 {@link InviteResponseEntity#getAckPlayBody}，
     * 回放用 {@code getAckPlayBackBody}（携带回放时间窗）。
     */
    private void sendInviteOk(ClientInviteEvent event, String userId, String sendIp, String sendPort,
        String ssrc, GbSessionDescription upSdp) {
        SipTransactionRegistry.TransactionContextInfo ctx =
            SipTransactionRegistry.getContext(event.getTransactionContextKey());
        if (ctx == null) {
            log.warn("事务上下文已失效: key={}", event.getTransactionContextKey());
            return;
        }
        int port = parsePort(sendPort);
        String safeSsrc = StringUtils.defaultIfBlank(ssrc, "0");
        String sdpBody;
        if (isPlayback(upSdp)) {
            String[] window = parsePlaybackWindow(upSdp);
            sdpBody = InviteResponseEntity.getAckPlayBackBody(
                userId, sendIp, port, parseGbTime(window[0]), parseGbTime(window[1]), safeSsrc).toString();
        } else {
            sdpBody = InviteResponseEntity.getAckPlayBody(userId, sendIp, port, safeSsrc).toString();
        }
        ResponseCmd.sendResponse(Response.OK, sdpBody,
            ContentTypeEnum.APPLICATION_SDP.getContentTypeHeader(),
            ctx.getOriginalEvent(), ctx.getServerTransaction());
    }

    private void sendInviteError(ClientInviteEvent event) {
        SipTransactionRegistry.TransactionContextInfo ctx =
            SipTransactionRegistry.getContext(event.getTransactionContextKey());
        if (ctx != null) {
            ResponseCmd.sendResponse(Response.SERVER_INTERNAL_ERROR,
                ctx.getOriginalEvent(), ctx.getServerTransaction());
        }
    }

    /** SDP s= 行：Playback / Download 视为回放，Play 为实时。 */
    private boolean isPlayback(GbSessionDescription sdp) {
        InviteSessionNameEnum type = sdp.getSessionType();
        if (type == null) {
            return false;
        }
        return type == InviteSessionNameEnum.PLAY_BACK || type == InviteSessionNameEnum.DOWNLOAD;
    }

    /** 回放时间窗：取 SDP t= 行 [start, stop]（GB28181 用 Unix 秒，直接读原始 t= 行避免 NTP 偏移）。 */
    private String[] parsePlaybackWindow(GbSessionDescription sdp) {
        String start = "0";
        String stop = "0";
        try {
            String raw = sdp.getBaseSdb() != null ? sdp.getBaseSdb().toString() : "";
            for (String line : raw.split("\\r?\\n")) {
                String l = line.trim();
                if (l.startsWith("t=")) {
                    String[] parts = l.substring(2).trim().split("\\s+");
                    if (parts.length >= 2) {
                        start = parts[0];
                        stop = parts[1];
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析回放时间窗失败，回退 0/0: {}", e.getMessage());
        }
        return new String[] {start, stop};
    }

    /** 从 host:port 提取 host。 */
    private String hostOf(String hostPort) {
        if (StringUtils.isBlank(hostPort)) {
            return hostPort;
        }
        int idx = hostPort.lastIndexOf(':');
        return idx > 0 ? hostPort.substring(0, idx) : hostPort;
    }

    private String parseCascadeChannelId(GbSessionDescription sdp) {
        if (sdp.getBaseSdb() == null) {
            return "";
        }
        try {
            return sdp.getBaseSdb().getSessionName().getValue();
        } catch (Exception e) {
            return "";
        }
    }

    private String parsePlatformId(GbSessionDescription sdp, String fallback) {
        if (sdp.getBaseSdb() == null) {
            return fallback;
        }
        try {
            return sdp.getBaseSdb().getOrigin().getUsername();
        } catch (Exception e) {
            return fallback;
        }
    }

    private int parseSsrc(String ssrc) {
        try {
            return Integer.parseInt(ssrc);
        } catch (Exception e) {
            return 0;
        }
    }

    private int parsePort(String port) {
        try {
            return Integer.parseInt(port);
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseGbTime(String t) {
        try {
            return Long.parseLong(t);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 媒体会话上下文。
     *
     * <p>INVITE 阶段只填会话参数（含已分配的本端发送端口），ACK 到达后据此调用 startSendRtp；
     * 之前先推流再回 200 的写法无法拿到 ACK 时机，会把"上级其实已放弃"的会话也推到 ZLM。
     */
    static class MediaSession {

        /** 上级指定的 SSRC（startSendRtp / stopSendRtp 的键） */
        final String  ssrc;
        /** 真实设备编码 */
        final String  localDeviceId;
        /** 上级寻址用的级联通道编码（INFO 回放控制按此反查会话） */
        final String  upperUserId;
        /** 是否回放会话 */
        final boolean playback;
        /** ZLM 应用名 */
        final String  app;
        /** ZLM 流 ID：本端设备_通道 */
        final String  stream;
        /** 上级收流地址 */
        final String  dstUrl;
        /** 上级收流端口 */
        final int     dstPort;
        /** 是否 UDP 收流 */
        final boolean udp;
        /** 本端 RTP 发送端口，与 200 OK SDP 中 m= 端口一致 */
        final int     srcPort;
        /** 会话建立时间，用于回收迟迟等不到 ACK 的待推流会话 */
        final long    createdAt;

        MediaSession(String ssrc, String localDeviceId, String upperUserId, boolean playback,
            String app, String stream, String dstUrl, int dstPort, boolean udp, int srcPort) {
            this.ssrc = ssrc;
            this.localDeviceId = localDeviceId;
            this.upperUserId = upperUserId;
            this.playback = playback;
            this.app = app;
            this.stream = stream;
            this.dstUrl = dstUrl;
            this.dstPort = dstPort;
            this.udp = udp;
            this.srcPort = srcPort;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
