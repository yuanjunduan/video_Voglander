package io.github.lunasaw.voglander.service.live.impl;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.alibaba.fastjson2.JSON;

import io.github.lunasaw.voglander.common.constant.media.MediaSessionConstant;
import io.github.lunasaw.voglander.common.enums.StreamProfileEnum;
import io.github.lunasaw.voglander.common.event.StreamOfflineEvent;
import io.github.lunasaw.voglander.common.exception.ServiceException;
import io.github.lunasaw.voglander.common.exception.ServiceExceptionEnum;
import io.github.lunasaw.voglander.manager.domaon.dto.MediaSessionDTO;
import io.github.lunasaw.voglander.manager.manager.MediaSessionManager;
import io.github.lunasaw.voglander.repository.cache.redis.RedisLockUtil;
import io.github.lunasaw.voglander.service.live.LiveSessionInfo;
import io.github.lunasaw.voglander.service.live.LiveStreamRegistry;
import io.github.lunasaw.voglander.service.live.MediaPlayService;
import io.github.lunasaw.voglander.service.live.codec.StreamCodecResolver;
import io.github.lunasaw.voglander.service.live.codec.StreamVideoInfo;
import io.github.lunasaw.voglander.service.live.dto.LivePlayDTO;
import io.github.lunasaw.voglander.service.live.dto.LiveStartDTO;
import io.github.lunasaw.voglander.service.live.dto.LiveStreamStatsDTO;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishContext;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishResult;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolHandler;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolRouter;
import io.github.lunasaw.voglander.service.live.protocol.MediaTerminateContext;
import io.github.lunasaw.voglander.service.sse.SseEvent;
import io.github.lunasaw.voglander.service.sse.SseEventBus;
import io.github.lunasaw.zlm.api.ZlmRestService;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.entity.MediaData;
import io.github.lunasaw.zlm.entity.MediaOnlineStatus;
import io.github.lunasaw.zlm.entity.PlayUrl;
import io.github.lunasaw.zlm.entity.ServerResponse;
import io.github.lunasaw.zlm.entity.Track;
import io.github.lunasaw.zlm.entity.req.MediaReq;
import io.github.lunasaw.zlm.node.NodeSupplier;
import io.github.lunasaw.zlm.node.service.NodeService;
import lombok.extern.slf4j.Slf4j;

/**
 * 媒体播放编排服务实现（直播）。
 *
 * @author luna
 */
@Slf4j
@Service
public class MediaPlayServiceImpl implements MediaPlayService {

    /** ZLM 收流 app（直播流统一落在 rtp 应用下） */
    private static final String  ZLM_APP             = "rtp";
    /** 首播等待流就绪超时（秒）。需 &lt; LOCK_HOLD_SEC(20)。8s 对 ffmpeg/真实摄像头冷启动偏紧，取 15s 留足余量 */
    private static final int     FUTURE_TIMEOUT_SEC  = 15;
    /** 会话保活 TTL（秒） */
    private static final long    KEEPALIVE_SEC       = 3600;
    /** 分布式锁持有时间（秒），需大于首播全流程耗时 */
    private static final int     LOCK_HOLD_SEC       = 20;
    /** 获取锁等待时间（秒） */
    private static final int     LOCK_WAIT_SEC       = 3;
    /**
     * refCount 归零后延迟回收窗口（秒）。
     * <p>
     * 必须 &gt; GC 间隔（{@link io.github.lunasaw.voglander.service.live.LiveSessionGcService} 的 60s），
     * 否则 key 常在 GC tick 前过期 → {@code drainPendingClose} 扫不到 → 永不真实关流。取 90s 保证至少一次 tick 命中；
     * TTL 仅作多节点兜底，不再是回收主路径。
     */
    private static final int     PENDING_CLOSE_SEC   = 90;
    /** 首播之后补问流指标、补写通道分辨率的延迟（秒）：给关键帧解析留时间，又不至于拖到用户切页 */
    private static final int     PROBE_BACKFILL_DELAY_SEC = 5;

    private static final String  LOCK_PREFIX         = "live:lock:";
    private static final String  PENDING_CLOSE_PREFIX = "live:pending_close:";
    /**
     * live.ready 上线幂等键前缀，必须与 {@code LiveStreamEventListener.LIVE_READY_NOTIFIED_PREFIX} 一致。
     * <p>
     * 关流收尾时删除该键：同一路 streamId 重新点播要能再收到一条 live.ready，
     * 不能因为上一次上线的幂等标记还在而被吞掉。
     * </p>
     */
    private static final String  LIVE_READY_NOTIFIED_PREFIX = "live:ready_notified:";

    /** 关流去重锁前缀。ZLM onStreamChanged(regist=false) 按 schema(hls/rtmp/fmp4/ts/rtsp) 各回调一次，
     *  5 个 hook 线程并发进 closeStream，靠本锁收敛为单次真实收尾，其余直接短路。 */
    private static final String  CLOSE_LOCK_PREFIX   = "live:close:";
    /** 关流锁持有时间（秒）：覆盖单次 closeRtpServer + sendBye + forceClose 全流程。 */
    private static final int     CLOSE_LOCK_HOLD_SEC = 10;

    @Autowired
    private NodeService                 nodeService;
    @Autowired
    private NodeSupplier                nodeSupplier;
    @Autowired
    private MediaSessionManager         mediaSessionManager;
    @Autowired
    private LiveStreamRegistry          liveStreamRegistry;
    @Autowired
    private MediaProtocolRouter         mediaProtocolRouter;
    @Autowired
    private StreamCodecResolver          streamCodecResolver;
    @Autowired
    private RedisLockUtil               redisLockUtil;
    @Autowired
    private SseEventBus                 sseEventBus;
    @Autowired
    private StringRedisTemplate         stringRedisTemplate;
    @Autowired
    private io.github.lunasaw.voglander.manager.manager.DeviceChannelManager deviceChannelManager;

    /**
     * 复用前可选探活开关（默认关闭，不污染热路径）。
     * <p>
     * 开启后，命中 ACTIVE 缓存的复用分支返回前会按会话原节点向 ZLM {@code isMediaOnline} 探活一次，
     * 应对"S1 下线回调 + S2 GC 对账都还没跑到、但缓存已死"的极小窗口。每次复用加一次 ZLM 往返，
     * 对延迟敏感场景默认不开；S1（秒级）+ S2（分钟级）已覆盖绝大多数死流。
     * </p>
     */
    @Value("${live.reuse-verify-enabled:false}")
    private boolean                     reuseVerifyEnabled;

    @Override
    public LivePlayDTO startLive(LiveStartDTO dto) {
        Assert.notNull(dto, "直播请求不能为空");
        Assert.hasText(dto.getDeviceId(), "deviceId不能为空");

        // 0. 按设备协议解析媒体协议处理器（决定流名前缀与通道校验策略；S5 新增协议只需新增 handler）
        MediaProtocolHandler handler = mediaProtocolRouter.resolveForDevice(dto.getDeviceId());
        if (handler == null) {
            throw new ServiceException(ServiceExceptionEnum.PARAM_ERROR, "设备无对应媒体协议处理器: " + dto.getDeviceId());
        }

        // 通道校验：仅“按通道寻址”的协议（GB28181）强制要求通道存在；RTSP/ONVIF 拉流允许无通道
        if (handler.requiresChannel()) {
            Assert.hasText(dto.getChannelId(), "channelId不能为空");
            io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO channel =
                deviceChannelManager.getDtoByDeviceId(dto.getDeviceId(), dto.getChannelId());
            if (channel == null) {
                throw new ServiceException(ServiceExceptionEnum.PARAM_ERROR,
                    "通道不存在: deviceId=" + dto.getDeviceId() + ", channelId=" + dto.getChannelId());
            }
        }

        String streamId = buildLiveStreamId(handler, dto.getDeviceId(), dto.getChannelId(), dto.getStreamProfile());
        String lockKey = LOCK_PREFIX + streamId;
        String lockValue = redisLockUtil.generateLockValue();

        boolean locked = Boolean.TRUE.equals(redisLockUtil.tryLock(lockKey, lockValue, LOCK_HOLD_SEC, LOCK_WAIT_SEC));
        if (!locked) {
            // 未拿到锁：可能另一线程正在首播，尝试复用已就绪会话
            LiveSessionInfo session = liveStreamRegistry.getSession(streamId);
            if (session != null && isActive(session.getStatus())) {
                liveStreamRegistry.incRef(streamId);
                return buildDTO(streamId, session);
            }
            throw new ServiceException(ServiceExceptionEnum.LIVE_INVITE_TIMEOUT);
        }

        try {
            // 0.5 切换码流（切换即替换）：先收回另一档码流，避免主/辅两路同时占设备带宽
            replaceOtherProfileIfNeeded(handler, dto, streamId);

            // 1. 多路复用：已有 ACTIVE 会话直接复用，秒返回
            LiveSessionInfo existing = liveStreamRegistry.getSession(streamId);
            if (existing != null && isActive(existing.getStatus())) {
                if (reuseVerifyEnabled && !reuseStreamAlive(existing, streamId)) {
                    // 缓存已死（探活查无），清掉，落入下方首播重建
                    liveStreamRegistry.remove(streamId);
                } else {
                    liveStreamRegistry.incRef(streamId);
                    liveStreamRegistry.keepAlive(streamId, KEEPALIVE_SEC);
                    return buildDTO(streamId, existing);
                }
            }

            // 2. 选节点（负载均衡），亲和由 nodeServerId 持久化承载
            ZlmNode node = selectNode();
            if (node == null) {
                throw new ServiceException(ServiceExceptionEnum.LIVE_NODE_UNAVAILABLE);
            }

            // 3. 媒体协议处理器已在入口解析（handler 变量），此处不再重复查库

            // 4. 预写 INVITING 占位会话（callId 暂用 streamId，建流成功后回填真实 callId）
            writePlaceholder(streamId, dto, node.getServerId());

            // 5. 注册首播等待 future（先注册，避免 onStreamChanged 先到找不到 future）
            CompletableFuture<Void> future = new CompletableFuture<>();
            liveStreamRegistry.registerFuture(streamId, future);

            // 6. 建流：协议特定地让流进 ZLM（GB28181: openRtpServer + INVITE，返回真实 Call-ID）
            MediaEstablishContext establishCtx = MediaEstablishContext.builder()
                .node(node)
                .streamId(streamId)
                .deviceId(dto.getDeviceId())
                .channelId(dto.getChannelId())
                .streamMode(dto.getStreamMode())
                .streamProfile(dto.getStreamProfile())
                .build();
            MediaEstablishResult establishResult = handler.establish(establishCtx);
            if (establishResult == null || !establishResult.isSuccess()) {
                String failReason = establishResult == null ? null : establishResult.getFailReason();
                cleanupFailed(streamId, node, handler);
                if (failReason == null || failReason.isBlank()) {
                    throw new ServiceException(ServiceExceptionEnum.LIVE_INVITE_TIMEOUT);
                }
                // 具体失败原因（如"设备未配置辅码流地址"）直透前端，错误码沿用 LIVE_INVITE_TIMEOUT
                throw new ServiceException(ServiceExceptionEnum.LIVE_INVITE_TIMEOUT.getCode(), failReason);
            }
            // 6.1 即刻把占位行 callId 回填为真实会话标识（关流据此终止，不依赖异步 InviteOk）
            String realCallId = establishResult.getCallId();
            if (realCallId != null && !realCallId.isBlank()) {
                try {
                    mediaSessionManager.backfillCallIdByStreamId(streamId, realCallId);
                } catch (Exception e) {
                    log.warn("回填真实 callId 失败, streamId={}: {}", streamId, e.getMessage());
                }
            }

            // 7. 等待流就绪（ZLM on_stream_changed 触发 future）
            try {
                future.get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                cleanupFailed(streamId, node, handler);
                sseEventBus.publish(new SseEvent("live.failed",
                    liveSseData(streamId, dto.getDeviceId(), dto.getChannelId(), "timeout")));
                throw new ServiceException(ServiceExceptionEnum.LIVE_INVITE_TIMEOUT);
            } catch (Exception e) {
                cleanupFailed(streamId, node, handler);
                throw new ServiceException(ServiceExceptionEnum.STREAM_NOT_READY, e.getMessage());
            }

            // 8. 拉 PlayUrls
            PlayUrl playUrl = fetchPlayUrls(node, streamId);

            // 8.1 识别视频编码：前端据此决定用原生播放器还是 WASM 软解。
            //     旁路信息，未就绪/查询失败返回 UNKNOWN，不阻断起流。
            StreamVideoInfo videoInfo = streamCodecResolver.resolve(node, ZLM_APP, streamId);
            // 8.2 回填通道编码（仅拉流类协议）：通道列表据此直接显示编码，不必先点播再问
            backfillChannelVideoProbe(handler, dto, videoInfo);
            // 8.3 分辨率补刀：建流应答里宽高常是 0x0（关键帧还没到），延迟几秒再问一次流指标
            scheduleProbeBackfill(handler, streamId, dto);

            // 9. 写 Registry + 引用计数 + 续约
            LiveSessionInfo info = new LiveSessionInfo();
            info.setNodeServerId(node.getServerId());
            info.setSdpIp(establishResult.getSdpIp());
            info.setRtpPort(establishResult.getRtpPort() == null ? 0 : establishResult.getRtpPort());
            info.setStatus(MediaSessionConstant.Status.ACTIVE);
            info.setSessionType(MediaSessionConstant.Type.PLAY);
            info.setCreateMs(System.currentTimeMillis());
            info.setPlayUrlsJson(playUrl == null ? null : JSON.toJSONString(playUrl));
            info.setVideoCodec(videoInfo.codec().name());
            info.setVideoWidth(videoInfo.width());
            info.setVideoHeight(videoInfo.height());
            liveStreamRegistry.putSession(streamId, info);
            liveStreamRegistry.incRef(streamId);
            liveStreamRegistry.keepAlive(streamId, KEEPALIVE_SEC);

            log.info("直播首播建立成功, streamId={}, node={}, rtpPort={}", streamId, node.getServerId(),
                establishResult.getRtpPort());
            return buildDTO(streamId, info);
        } finally {
            redisLockUtil.unLock(lockKey, lockValue);
        }
    }

    /**
     * 建流成功后把视频编码/分辨率回填到通道扩展（失败只记日志，不影响播放）。
     *
     * <p>
     * 编码为 {@code UNKNOWN} 时跳过：那是"没查到视频轨"的占位值，
     * 写进库会把上次探测到的真实编码冲掉。
     * </p>
     */
    private void backfillChannelVideoProbe(MediaProtocolHandler handler, LiveStartDTO dto, StreamVideoInfo videoInfo) {
        if (!handler.backfillChannelVideoProbe() || videoInfo == null || videoInfo.codec().isUnknown()) {
            return;
        }
        if (dto.getChannelId() == null || dto.getChannelId().isBlank()) {
            return;
        }
        try {
            deviceChannelManager.updateChannelVideoProbe(dto.getDeviceId(), dto.getChannelId(),
                videoInfo.codec().name(), videoInfo.width(), videoInfo.height());
        } catch (Exception e) {
            // 编码回填属旁路信息，锁/库异常都不能让已经建立好的播放失败
            log.warn("回填通道编码失败, deviceId={}, channelId={}: {}",
                dto.getDeviceId(), dto.getChannelId(), e.getMessage());
        }
    }

    /**
     * 分辨率补刀：首播应答里宽高常是 0x0（关键帧还没到），
     * {@link io.github.lunasaw.voglander.manager.manager.DeviceChannelManager#updateChannelVideoProbe}
     * 会把它当"未知"丢弃，于是通道列表的分辨率一直显示 {@code —}。
     * 延迟几秒问一次流指标，拿到真实宽高再补写；纯旁路，失败只记日志。
     */
    private void scheduleProbeBackfill(MediaProtocolHandler handler, String streamId, LiveStartDTO dto) {
        if (!handler.backfillChannelVideoProbe()) {
            return;
        }
        if (dto.getChannelId() == null || dto.getChannelId().isBlank()) {
            return;
        }
        CompletableFuture.delayedExecutor(PROBE_BACKFILL_DELAY_SEC, TimeUnit.SECONDS).execute(() -> {
            try {
                LiveStreamStatsDTO stats = getStreamStats(streamId);
                if (!stats.isOnline() || stats.getWidth() == null || stats.getHeight() == null
                    || stats.getWidth() <= 0 || stats.getHeight() <= 0) {
                    return;
                }
                deviceChannelManager.updateChannelVideoProbe(dto.getDeviceId(), dto.getChannelId(),
                    stats.getCodec(), stats.getWidth(), stats.getHeight());
            } catch (Exception e) {
                log.warn("补写通道分辨率失败, deviceId={}, channelId={}: {}",
                    dto.getDeviceId(), dto.getChannelId(), e.getMessage());
            }
        });
    }

    @Override
    public boolean stopLive(String streamId) {
        Assert.hasText(streamId, "streamId不能为空");
        long ref = liveStreamRegistry.decRef(streamId);
        if (ref > 0) {
            // 仍有观看者，保活
            liveStreamRegistry.keepAlive(streamId, KEEPALIVE_SEC);
            return true;
        }
        // 归零：标记延迟回收，真正 BYE 由 GC drainPendingClose 执行
        stringRedisTemplate.opsForValue().set(
            PENDING_CLOSE_PREFIX + streamId, "1", PENDING_CLOSE_SEC, TimeUnit.SECONDS);
        log.info("直播引用计数归零，标记延迟回收, streamId={}", streamId);
        return true;
    }

    @Override
    public LivePlayDTO getLive(String streamId) {
        Assert.hasText(streamId, "streamId不能为空");
        LiveSessionInfo session = liveStreamRegistry.getSession(streamId);
        if (session == null) {
            throw new ServiceException(ServiceExceptionEnum.LIVE_STREAM_NOT_FOUND);
        }
        return buildDTO(streamId, session);
    }

    @Override
    public LiveStreamStatsDTO getStreamStats(String streamId) {
        Assert.hasText(streamId, "streamId不能为空");
        LiveStreamStatsDTO stats = new LiveStreamStatsDTO();
        stats.setStreamId(streamId);
        stats.setOnline(false);

        // 会话定位节点：多节点部署下必须问「这条流落在哪个节点」，不能走负载均衡重选
        MediaSessionDTO session = mediaSessionManager.getByStreamId(streamId);
        if (session == null || session.getNodeServerId() == null) {
            return stats;
        }
        ZlmNode node;
        try {
            node = nodeService.getAvailableNode(session.getNodeServerId());
        } catch (Exception e) {
            log.warn("[getStreamStats] 解析会话节点失败, streamId={}: {}", streamId, e.getMessage());
            return stats;
        }
        if (node == null) {
            return stats;
        }

        try {
            MediaReq req = new MediaReq();
            req.setApp(ZLM_APP);
            req.setStream(streamId);
            ServerResponse<List<MediaData>> resp =
                ZlmRestService.getMediaList(node.getHost(), node.getSecret(), req);
            if (resp == null || resp.getCode() == null || resp.getCode() != 0 || resp.getData() == null) {
                log.debug("[getStreamStats] ZLM 未返回有效流列表, streamId={}", streamId);
                return stats;
            }
            MediaData media = pickMedia(streamId, resp.getData());
            if (media == null) {
                return stats;
            }
            fillStats(stats, media);
        } catch (Exception e) {
            // 调试指标属旁路信息，不允许向上抛：前端按秒轮询，抛错会刷满错误提示
            log.warn("[getStreamStats] 查询流指标异常, streamId={}: {}", streamId, e.getMessage());
        }
        return stats;
    }

    @Override
    public void keepAlive(String streamId) {
        Assert.hasText(streamId, "streamId不能为空");
        liveStreamRegistry.keepAlive(streamId, KEEPALIVE_SEC);
        // 续约即取消延迟回收标记
        stringRedisTemplate.delete(PENDING_CLOSE_PREFIX + streamId);
    }

    @Override
    public void closeStream(String streamId) {
        closeStream(streamId, "idle_gc");
    }

    @Override
    public void closeStream(String streamId, String reason) {
        Assert.hasText(streamId, "streamId不能为空");
        String sseReason = (reason == null || reason.isBlank()) ? "idle_gc" : reason;

        // 关流去重：ZLM 多 schema 并发回调下，仅首个抢到锁的线程执行真实收尾，其余短路。
        // 抢锁失败即视为"已有线程在关"，直接返回（幂等：真正的 BYE/forceClose 只发一次）。
        // 用单次非阻塞 lock（非 tryLock 退避重试），抢不到立即放弃。
        String closeLockKey = CLOSE_LOCK_PREFIX + streamId;
        String closeLockValue = redisLockUtil.generateLockValue();
        boolean closeLocked = Boolean.TRUE.equals(
            redisLockUtil.lock(closeLockKey, closeLockValue, CLOSE_LOCK_HOLD_SEC));
        if (!closeLocked) {
            log.debug("[closeStream] 已有线程在关流，跳过重复收尾, streamId={}, reason={}", streamId, sseReason);
            return;
        }
        try {
            doCloseStream(streamId, sseReason);
        } finally {
            redisLockUtil.unLock(closeLockKey, closeLockValue);
        }
    }

    /**
     * 关流真实收尾（已被 {@link #closeStream(String, String)} 的去重锁保护，单次执行）。
     */
    private void doCloseStream(String streamId, String sseReason) {
        MediaSessionDTO session = mediaSessionManager.getByStreamId(streamId);

        // 0. 迟到回调保护：ZLM 对同一条流按 hls/rtsp/rtmp/fmp4/ts 逐个回调，切码流（profile_switch 关旧档）
        //    后，旧那一代的 on_stream_changed(regist=false) 常在"同名新流已建好"之后才到。
        //    主/辅切换复用同一个 streamId，照常收尾就会把刚起的新流直接关掉——
        //    前端表现为"主码流/辅码流来回切一次就黑屏"。判据交给 ZLM：这条流此刻仍在线，
        //    说明回调针对的是已不存在的那一代流，跳过收尾。
        if (StreamOfflineEvent.REASON_STREAM_OFFLINE.equals(sseReason) && isStreamAliveInZlm(streamId, session)) {
            log.info("[closeStream] 流在 ZLM 仍在线，判定为迟到的下线回调，跳过收尾, streamId={}", streamId);
            return;
        }

        // 1. 协议特定收尾：关收流端 + 终止协议会话（GB28181: closeRtpServer + sendBye）。
        //    按设备协议解析 handler；解析失败（设备已删等）跳过协议收尾，不阻断后续幂等清理。
        if (session != null) {
            MediaProtocolHandler handler = mediaProtocolRouter.resolveForDevice(session.getDeviceId());
            if (handler != null) {
                ZlmNode node = null;
                if (session.getNodeServerId() != null) {
                    try {
                        node = nodeService.getAvailableNode(session.getNodeServerId());
                    } catch (Exception e) {
                        log.warn("[closeStream] 解析会话节点失败, streamId={}: {}", streamId, e.getMessage());
                    }
                }
                try {
                    handler.terminate(MediaTerminateContext.builder()
                        .node(node)
                        .streamId(streamId)
                        .callId(session.getCallId())
                        .reason(sseReason)
                        .build());
                } catch (Exception e) {
                    log.warn("[closeStream] 协议收尾失败, streamId={}: {}", streamId, e.getMessage());
                }
            } else {
                log.warn("[closeStream] 无对应媒体���议处理器，跳过协议收尾, streamId={}, deviceId={}",
                    streamId, session.getDeviceId());
            }
        }

        // 2. 标会话 CLOSED
        if (session != null && session.getId() != null) {
            try {
                mediaSessionManager.forceClose(session.getId());
            } catch (Exception e) {
                log.warn("[closeStream] 标记会话 CLOSED 失败, streamId={}: {}", streamId, e.getMessage());
            }
        }

        // 3. SSE live.closed（reason 透传来源）+ 4. 清 Registry + 5. 删 pending_close key（幂等收尾，总是执行）
        sseEventBus.publish(new SseEvent("live.closed",
            liveSseData(streamId,
                session == null ? null : session.getDeviceId(),
                session == null ? null : session.getChannelId(),
                sseReason)));
        liveStreamRegistry.remove(streamId);
        stringRedisTemplate.delete(PENDING_CLOSE_PREFIX + streamId);
        // 清上线幂等标记：同一路流重新点播时要能再收到 live.ready
        stringRedisTemplate.delete(LIVE_READY_NOTIFIED_PREFIX + streamId);
        log.info("[closeStream] 关流完成, streamId={}, reason={}", streamId, sseReason);
    }

    // ================================
    // 私有辅助
    // ================================

    /**
     * 从 ZLM 流列表挑出目标流。
     * <p>
     * 同一条流按 schema（ts/rtsp/fmp4/hls/rtmp…）会各返回一条，编码相同；
     * 优先取视频轨已就绪的那条，全部无视频轨时退回首个同名流（至少能报码率与观看数）。
     * </p>
     */
    private MediaData pickMedia(String streamId, List<MediaData> mediaList) {
        MediaData fallback = null;
        for (MediaData media : mediaList) {
            if (media == null || !streamId.equals(media.getStream())) {
                continue;
            }
            if (firstVideoTrack(media.getTracks()) != null) {
                return media;
            }
            if (fallback == null) {
                fallback = media;
            }
        }
        return fallback;
    }

    private void fillStats(LiveStreamStatsDTO stats, MediaData media) {
        stats.setOnline(true);
        stats.setBytesSpeed((long) media.getBytesSpeed());
        stats.setReaderCount(media.getReaderCount());
        stats.setTotalReaderCount(media.getTotalReaderCount());
        stats.setAliveSecond(media.getAliveSecond());
        stats.setOriginUrl(media.getOriginUrl());
        Track video = firstVideoTrack(media.getTracks());
        if (video == null) {
            return;
        }
        stats.setCodec(video.getCodecIdName());
        stats.setWidth(video.getWidth());
        stats.setHeight(video.getHeight());
        // Track#getFps 为 double，指标面板按整数帧率展示
        stats.setFps((int) Math.round(video.getFps()));
    }

    /** 取首个视频轨：{@code codec_type} 为 0 才是视频（1 = 音频），不筛会把音频编码当视频。 */
    private static Track firstVideoTrack(Track[] tracks) {
        if (tracks == null) {
            return null;
        }
        for (Track track : tracks) {
            if (track != null && track.getCodecType() == 0) {
                return track;
            }
        }
        return null;
    }

    /**
     * 构造 live.* SSE 事件数据。
     * <p>
     * 必须携带 deviceId：前端「设备详情」按 deviceId 过滤事件帧，只带 streamId 的帧会被整体丢弃；
     * 而无 SIP 信令的拉流设备（RTSP/ONVIF）唯一的事件来源就是 live.*，漏带则时间线永远为空。
     * 必须携带 ts：前端按 {@code topic|data.ts|设备字段} 去重，缺 ts 时同一路流的同类事件键恒定，
     * 第二次点播/关流会被当成重复帧整帧丢弃（表现为"再点播就不出事件了"）。
     * 这里不用 {@code Map.of}：其不接受 null 值，设备/通道缺失时会抛 NPE 打断关流收尾。
     * </p>
     */
    private java.util.Map<String, Object> liveSseData(String streamId, String deviceId, String channelId,
        String reason) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>(8);
        data.put("streamId", streamId == null ? "" : streamId);
        data.put("deviceId", deviceId == null ? "" : deviceId);
        data.put("channelId", channelId == null ? "" : channelId);
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason);
        }
        data.put("ts", System.currentTimeMillis());
        return data;
    }

    /**
     * 生成前端稳定主键 / ZLM 流名：{协议前缀}{deviceId}[_{channelId}][_sub]。
     * <p>
     * 前缀由协议处理器决定（GB28181 = {@code gb_live_}，RTSP/ONVIF = {@code rtsp_live_}）；
     * 无通道的拉流设备不拼通道段，避免 channelId 以 deviceId 兜底导致流名重复。
     * </p>
     * <p>
     * 码流档次必须编码进 streamId：它是会话复用键（命中 ACTIVE 直接 incRef 返回），
     * 若主/辅共用同一 streamId，切码流会被当成"复用"直接返回旧流，切换永远无效。
     * 主码流不加后缀，存量流名/播放地址/会话完全不变。
     * </p>
     */
    private String buildLiveStreamId(MediaProtocolHandler handler, String deviceId, String channelId,
        String streamProfile) {
        String base = (channelId == null || channelId.isBlank())
            ? handler.liveStreamIdPrefix() + deviceId
            : handler.liveStreamIdPrefix() + deviceId + "_" + channelId;
        return StreamProfileEnum.isSub(streamProfile) ? base + StreamProfileEnum.SUB_STREAM_SUFFIX : base;
    }

    /**
     * 切换码流（切换即替换）：建新码流前先回收另一档码流。
     * <p>
     * 仅当另一档无人观看（refCount ≤ 1，即只有发起切换的这个观看者）时回收；
     * 有其他观看者则整单拒绝，不把别人的画面踢掉。
     * 回收走 {@link #closeStream(String, String)} 的完整收尾（协议终止 + 标 CLOSED + SSE + 清 Registry），
     * 不等 pending_close 的 90s 延迟，避免主/辅两路同时占设备带宽。
     * </p>
     */
    private void replaceOtherProfileIfNeeded(MediaProtocolHandler handler, LiveStartDTO dto, String streamId) {
        if (!dto.isReplacePrevious()) {
            return;
        }
        String otherStreamId = buildLiveStreamId(handler, dto.getDeviceId(), dto.getChannelId(),
            StreamProfileEnum.opposite(dto.getStreamProfile()).getCode());
        if (otherStreamId.equals(streamId)) {
            return;
        }
        LiveSessionInfo other = liveStreamRegistry.getSession(otherStreamId);
        if (other == null || !isActive(other.getStatus())) {
            return;
        }
        long otherRef = liveStreamRegistry.getRef(otherStreamId);
        if (otherRef > 1) {
            log.info("[profile] 另一档码流仍有观看者，拒绝切换, other={}, ref={}", otherStreamId, otherRef);
            throw new ServiceException(ServiceExceptionEnum.PARAM_ERROR.getCode(),
                "另一路码流正在被其他用户观看，暂不支持切换码流");
        }
        log.info("[profile] 切换码流，立即回收另一档, from={}, to={}, ref={}", otherStreamId, streamId, otherRef);
        closeStream(otherStreamId, "profile_switch");
    }

    private boolean isActive(Integer status) {
        return status != null && status == MediaSessionConstant.Status.ACTIVE;
    }

    /**
     * 选节点：负载均衡主选；主选不可用时按 weight 降序从候选 enabled 节点兜底（S6.1 故障转移）。
     * 节点亲和通过会话持久化的 nodeServerId 承载，首播按 LB 落点。
     */
    private ZlmNode selectNode() {
        ZlmNode primary = null;
        try {
            primary = nodeService.selectNode();
        } catch (Exception e) {
            log.warn("负载均衡选节点失败，尝试候选兜底: {}", e.getMessage());
        }
        if (primary != null) {
            return primary;
        }
        // 主选为空：按 weight 降序取候选 enabled 节点（单节点故障不再直接打断点播）
        List<ZlmNode> candidates = null;
        try {
            candidates = nodeSupplier.getNodes();
        } catch (Exception e) {
            log.warn("获取候选节点列表失败: {}", e.getMessage());
        }
        ZlmNode fallback = chooseNode(null, candidates);
        if (fallback != null) {
            log.warn("主选节点不可用，故障转移到候选节点: serverId={}, weight={}",
                fallback.getServerId(), fallback.getWeight());
        }
        return fallback;
    }

    /**
     * 节点选择纯逻辑（可单测）：主选命中即用；否则从候选按 weight 降序取首个 enabled。
     *
     * @param primary    负载均衡主选（可空）
     * @param candidates 候选节点列表（可空）
     * @return 选中节点，全不可用返回 null
     */
    static ZlmNode chooseNode(ZlmNode primary, List<ZlmNode> candidates) {
        if (primary != null) {
            return primary;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
            .filter(java.util.Objects::nonNull)
            .filter(ZlmNode::isEnabled)
            .max(java.util.Comparator.comparingInt(ZlmNode::getWeight))
            .orElse(null);
    }

    private void writePlaceholder(String streamId, LiveStartDTO dto, String nodeServerId) {
        // 清理可能残留的同 streamId 旧行（Redis 会话已过期但 DB 行未清），避免 UNIQUE 冲突
        MediaSessionDTO old = mediaSessionManager.getByStreamId(streamId);
        if (old != null) {
            MediaSessionDTO del = new MediaSessionDTO();
            del.setId(old.getId());
            mediaSessionManager.deleteOne(del);
        }
        MediaSessionDTO placeholder = new MediaSessionDTO();
        placeholder.setCallId(streamId);
        placeholder.setStreamId(streamId);
        placeholder.setDeviceId(dto.getDeviceId());
        placeholder.setChannelId(dto.getChannelId());
        placeholder.setNodeServerId(nodeServerId);
        placeholder.setStatus(MediaSessionConstant.Status.INVITING);
        placeholder.setSessionType(MediaSessionConstant.Type.PLAY);
        placeholder.setRefCount(0);
        mediaSessionManager.add(placeholder);
    }

    private PlayUrl fetchPlayUrls(ZlmNode node, String streamId) {
        try {
            MediaReq mediaReq = new MediaReq();
            mediaReq.setApp(ZLM_APP);
            mediaReq.setStream(streamId);
            ServerResponse<PlayUrl> resp = ZlmRestService.getPlaybackUrls(node.getHost(), node.getSecret(), mediaReq);
            if (resp != null && resp.getCode() != null && resp.getCode() == 0) {
                return resp.getData();
            }
            log.warn("getPlaybackUrls 返回非成功, streamId={}, resp={}", streamId, resp);
        } catch (Exception e) {
            log.warn("getPlaybackUrls 异常, streamId={}: {}", streamId, e.getMessage());
        }
        return null;
    }

    /**
     * 按会话所在节点向 ZLM 确认该 stream 是否仍有在线实例（{@code isMediaOnline}）。
     * <p>
     * 只用于识别"迟到的下线回调"：主/辅切换复用同一 streamId，旧一代流的 regist=false 回调
     * 会在新流建好之后到达，若此时 ZLM 里这条流仍在线，说明回调与当前流无关，不能照常收尾。
     * 无节点信息 / 查询异常一律返回 false（按已下线处理）：宁可关掉由 GC 兜底重建，
     * 也不要为一次网络抖动把真实下线挡在门外、让死会话长期挂着。
     * </p>
     * <p>
     * 包级可见而非 private：判定依赖 ZLM 的静态 HTTP 调用，单测用 spy 覆盖本方法
     * 比起一个假 ZLM 更直接（见 MediaPlayServiceCloseStreamTest）。
     * </p>
     */
    boolean isStreamAliveInZlm(String streamId, MediaSessionDTO session) {
        try {
            String serverId = session == null ? null : session.getNodeServerId();
            if (serverId == null || serverId.isBlank()) {
                return false;
            }
            ZlmNode node = nodeService.getAvailableNode(serverId);
            if (node == null) {
                return false;
            }
            MediaReq req = new MediaReq();
            req.setApp(ZLM_APP);
            req.setStream(streamId);
            MediaOnlineStatus st = ZlmRestService.isMediaOnline(node.getHost(), node.getSecret(), req);
            return st != null && Boolean.TRUE.equals(st.getOnline());
        } catch (Exception e) {
            log.warn("[closeStream] 流在线性校验失败，按已下线处理, streamId={}: {}", streamId, e.getMessage());
            return false;
        }
    }

    /**
     * 复用前探活：按会话<b>原节点</b>（{@code info.nodeServerId}，而非重新负载均衡）向 ZLM
     * {@code isMediaOnline} 核实流是否存活。无节点信息 / 查询异常时保守返回 true（保留复用，不误判）。
     * 与 GC 对账同款判活语义，仅用于 {@code reuseVerifyEnabled} 开启时。
     */
    private boolean reuseStreamAlive(LiveSessionInfo info, String streamId) {
        try {
            String serverId = info.getNodeServerId();
            if (serverId == null) {
                return true;
            }
            ZlmNode node = nodeService.getAvailableNode(serverId);
            if (node == null) {
                return true;
            }
            MediaReq req = new MediaReq();
            req.setApp(ZLM_APP);
            req.setStream(streamId);
            MediaOnlineStatus st = ZlmRestService.isMediaOnline(node.getHost(), node.getSecret(), req);
            return st != null && Boolean.TRUE.equals(st.getOnline());
        } catch (Exception e) {
            log.warn("[reuse] 探活异常，保守复用, streamId={}: {}", streamId, e.getMessage());
            return true;
        }
    }

    private void cleanupFailed(String streamId, ZlmNode node, MediaProtocolHandler handler) {
        // 协议特定收尾（建流失败回滚）：关收流端 + 终止会话。callId 此时多为空（建流即失败），terminate 幂等跳过。
        try {
            MediaSessionDTO row = mediaSessionManager.getByStreamId(streamId);
            String callId = row != null ? row.getCallId() : null;
            handler.terminate(MediaTerminateContext.builder()
                .node(node).streamId(streamId).callId(callId).reason("establish_failed").build());
        } catch (Exception e) {
            log.warn("建流失败回滚协议收尾异常, streamId={}: {}", streamId, e.getMessage());
        }
        try {
            MediaSessionDTO row = mediaSessionManager.getByStreamId(streamId);
            if (row != null) {
                mediaSessionManager.onInviteFailure(row.getCallId(), 408);
            }
        } catch (Exception e) {
            log.warn("标记会话失败状态异常, streamId={}: {}", streamId, e.getMessage());
        }
        liveStreamRegistry.remove(streamId);
    }

    private LivePlayDTO buildDTO(String streamId, LiveSessionInfo info) {
        LivePlayDTO dto = new LivePlayDTO();
        dto.setStreamId(streamId);
        dto.setCallId(info.getCallId());
        dto.setStatus(info.getStatus());
        dto.setRefCount(liveStreamRegistry.getRef(streamId));
        if (info.getPlayUrlsJson() != null) {
            try {
                dto.setPlayUrls(JSON.parseObject(info.getPlayUrlsJson(), PlayUrl.class));
            } catch (Exception ignored) {
            }
        }
        dto.setVideoCodec(info.getVideoCodec());
        dto.setVideoWidth(info.getVideoWidth());
        dto.setVideoHeight(info.getVideoHeight());
        return dto;
    }
}
