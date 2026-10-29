package io.github.lunasaw.voglander.service.live.protocol.impl;

import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.luna.common.dto.ResultDTO;

import io.github.lunasaw.voglander.common.enums.DeviceProtocolEnum;
import io.github.lunasaw.voglander.common.enums.StreamProfileEnum;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.StreamProxyZlmWrapperService;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.dto.StreamProxyDeleteRequest;
import io.github.lunasaw.voglander.intergration.wrapper.zlm.service.dto.StreamProxyRequest;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceChannelDTO;
import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;
import io.github.lunasaw.voglander.manager.manager.DeviceChannelManager;
import io.github.lunasaw.voglander.manager.manager.DeviceManager;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishContext;
import io.github.lunasaw.voglander.service.live.protocol.MediaEstablishResult;
import io.github.lunasaw.voglander.service.live.protocol.MediaProtocolHandler;
import io.github.lunasaw.voglander.service.live.protocol.MediaTerminateContext;
import io.github.lunasaw.zlm.config.ZlmNode;
import io.github.lunasaw.zlm.entity.StreamKey;
import io.github.lunasaw.zlm.entity.StreamProxyItem;
import lombok.extern.slf4j.Slf4j;

/**
 * RTSP / ONVIF 媒体协议处理器（拉流模型）。
 * <p>
 * "ZLM 主动拉流"模型：建流 = {@code addStreamProxy}（ZLM 去设备侧拉 {@code rtsp://}，转封装为
 * RTSP/RTMP/HTTP-FLV/HLS 供播放）；终止 = {@code delStreamProxy}。与 GB28181 的
 * {@code openRtpServer + INVITE}（设备推 RTP）互为对称，编排层（{@code MediaPlayServiceImpl}）无需感知差异。
 * </p>
 * <p>
 * RTSP 地址来源（通道优先、设备回落）：带 {@code channelId} 时取
 * {@code tb_device_channel.extend.rtspUrl/rtspSubUrl}（由通道发现写入）；否则取
 * {@code tb_device.extend.rtspUrl/rtspSubUrl}（见 {@link DeviceDTO.ExtendInfo}）。
 * 同一个 NVR 下的多路通道因此可以共用一个设备记录、各自点播。
 * 协议会话标识复用 {@code callId} 字段承载 ZLM 代理 key，关流时据此精确删除代理。
 * </p>
 *
 * @author luna
 */
@Slf4j
@Component
public class RtspMediaProtocolHandler implements MediaProtocolHandler {

    /** 流统一落在 ZLM 的 rtp 应用下，与 GB28181 直播共用播放地址拼装规则 */
    private static final String ZLM_APP        = "rtp";
    /** ZLM 默认虚拟主机 */
    private static final String DEFAULT_VHOST  = "__defaultVhost__";
    /** 拉流协议：0=TCP（对摄像头更稳），1=UDP */
    private static final int    RTP_TYPE_TCP   = 0;
    /** 拉流超时（秒） */
    private static final int    TIMEOUT_SEC    = 10;
    /**
     * 拉流是否携带音频轨。
     *
     * <p>
     * 关闭。部分设备（实测海康 NVR 的辅码流）在 SDP 里声明了音频轨，但从不发送音频数据；
     * ZLM 的 {@code general.wait_track_ready_ms}（默认 10s）要等所有轨都有帧才注册媒体源，
     * 注册之前 HTTP-FLV/TS/HLS 一个字节都不吐。媒体源晚注册 10s，前端就是"主/辅码流
     * 来回切一次直接黑屏"。只拉视频后媒体源随首个视频关键帧注册（实测首包 8.5s → 20ms）。
     * </p>
     * <p>
     * 代价是 RTSP/ONVIF 通道没有声音。要恢复音频时把这里改成 {@code true}，
     * 同时把 ZLM {@code general.wait_track_ready_ms} 调小，否则遇到"声明音频但不发数据"的源会重现黑屏。
     * </p>
     */
    private static final boolean ENABLE_AUDIO   = false;

    @Autowired
    private DeviceManager                  deviceManager;
    @Autowired
    private DeviceChannelManager           deviceChannelManager;
    @Autowired
    private StreamProxyZlmWrapperService   streamProxyZlmWrapperService;

    @Override
    public Set<Integer> supportProtocols() {
        return Set.of(DeviceProtocolEnum.RTSP.getType(), DeviceProtocolEnum.ONVIF.getType());
    }

    /**
     * 拉流模型的流名前缀，与 GB28181 的 {@code gb_live_} 区分，便于 Hook 侧识别受管流。
     */
    @Override
    public String liveStreamIdPrefix() {
        return "rtsp_live_";
    }

    /**
     * 拉流不强制要求通道：带 channelId 时用通道自己的地址，不带则用设备级地址，
     * 允许无通道记录的存量设备直接点播。
     */
    @Override
    public boolean requiresChannel() {
        return false;
    }

    /**
     * 拉流通道的编码只能拉起来才知道，建流成功后回填到通道扩展，通道列表即可直接显示编码。
     */
    @Override
    public boolean backfillChannelVideoProbe() {
        return true;
    }

    @Override
    public MediaEstablishResult establish(MediaEstablishContext ctx) {
        ZlmNode node = ctx.getNode();
        String streamId = ctx.getStreamId();
        boolean subProfile = StreamProfileEnum.isSub(ctx.getStreamProfile());
        String rtspUrl = resolveRtspUrl(ctx.getDeviceId(), ctx.getChannelId(), subProfile);
        if (rtspUrl == null) {
            String target = isBlank(ctx.getChannelId()) ? "设备" : "通道[" + ctx.getChannelId() + "]";
            if (subProfile) {
                log.warn("[rtsp.establish] {}未配置辅码流地址, deviceId={}", target, ctx.getDeviceId());
                return MediaEstablishResult.failure(target + "未配置辅码流地址(rtspSubUrl)");
            }
            log.warn("[rtsp.establish] {}未配置 RTSP 地址, deviceId={}", target, ctx.getDeviceId());
            return MediaEstablishResult.failure(target + "未配置 RTSP 地址(rtspUrl)");
        }

        StreamProxyItem item = buildProxyItem(streamId, rtspUrl);
        ResultDTO<StreamKey> result = streamProxyZlmWrapperService.addStreamProxy(
            StreamProxyRequest.of(node.getHost(), node.getSecret(), item));
        if (result == null || result.getData() == null || result.getData().getKey() == null) {
            log.warn("[rtsp.establish] addStreamProxy 失败, streamId={}, node={}, url={}", streamId, node.getServerId(), rtspUrl);
            return MediaEstablishResult.failure("addStreamProxy 失败");
        }
        String proxyKey = result.getData().getKey();
        log.info("[rtsp.establish] 拉流代理已创建, streamId={}, node={}, url={}, proxyKey={}",
            streamId, node.getServerId(), rtspUrl, proxyKey);
        // 拉流无收流端点：sdpIp / rtpPort 为空，代理 key 复用 callId 承载，供关流精确删除
        return MediaEstablishResult.success(proxyKey, null, null);
    }

    @Override
    public void terminate(MediaTerminateContext ctx) {
        if (ctx.getNode() == null || ctx.getStreamId() == null) {
            return;
        }
        String proxyKey = ctx.getCallId();
        if (proxyKey == null || proxyKey.isBlank() || proxyKey.equals(ctx.getStreamId())) {
            // 建流即失败或占位行未回填：按 ZLM 代理 key 规则兜底删除，避免残留代理反复重连
            proxyKey = DEFAULT_VHOST + "/" + ZLM_APP + "/" + ctx.getStreamId();
            log.warn("[rtsp.terminate] 无有效 proxyKey，按规则兜底删除, streamId={}, proxyKey={}",
                ctx.getStreamId(), proxyKey);
        }
        try {
            ResultDTO<StreamKey.StringDelFlag> result = streamProxyZlmWrapperService.deleteStreamProxy(
                StreamProxyDeleteRequest.of(ctx.getNode().getHost(), ctx.getNode().getSecret(), proxyKey));
            if (result == null || result.getData() == null) {
                log.warn("[rtsp.terminate] 删除拉流代理未成功, streamId={}, proxyKey={}, reason={}",
                    ctx.getStreamId(), proxyKey, ctx.getReason());
            } else {
                log.info("[rtsp.terminate] 拉流代理已删除, streamId={}, proxyKey={}, reason={}",
                    ctx.getStreamId(), proxyKey, ctx.getReason());
            }
        } catch (Exception e) {
            // 幂等：代理已不存在等情况不阻断后续清理
            log.warn("[rtsp.terminate] 删除拉流代理异常, streamId={}, proxyKey={}: {}",
                ctx.getStreamId(), proxyKey, e.getMessage());
        }
    }

    /**
     * 组装 ZLM 拉流代理参数：转封装协议与 GB28181 直播保持一致（RTSP/RTMP/FLV/HLS/TS/fMP4）。
     */
    private StreamProxyItem buildProxyItem(String streamId, String rtspUrl) {
        StreamProxyItem item = new StreamProxyItem();
        item.setVHost(DEFAULT_VHOST);
        item.setApp(ZLM_APP);
        item.setStream(streamId);
        item.setUrl(rtspUrl);
        item.setRtpType(RTP_TYPE_TCP);
        item.setTimeoutSec(TIMEOUT_SEC);
        item.setEnableRtsp(true);
        item.setEnableRtmp(true);
        item.setEnableHls(true);
        item.setEnableHlsFmp4(false);
        item.setEnableTs(true);
        item.setEnableFmp4(true);
        item.setEnableMp4(false);
        item.setEnableAudio(ENABLE_AUDIO);
        // 源断线由 ZLM Hook（onStreamChanged regist=false）驱动收尾，代理不自动关闭以便重连
        item.setAutoClose(false);
        return item;
    }

    /**
     * 解析本次要拉的 RTSP 地址：通道优先、设备回落。
     *
     * <p>
     * 带 {@code channelId} 且通道表里确有该通道时，只认通道自己的地址——
     * 通道存在但缺这一路地址就直接报错，**不回落设备级**：NVR 场景下设备级地址指向的是
     * 某一路具体通道，回落会让用户以为在看通道 4，实际播的是通道 301。
     * 没带通道或通道不存在时才回落设备级，保证存量单设备（无通道记录）零影响。
     * </p>
     *
     * @param deviceId   设备 ID
     * @param channelId  通道 ID（可空）
     * @param subProfile true 取辅码流地址，false 取主码流
     * @return 规范化后的 rtsp:// 地址，未配置返回 null
     */
    private String resolveRtspUrl(String deviceId, String channelId, boolean subProfile) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        DeviceChannelDTO channel = loadChannel(deviceId, channelId);
        if (channel != null) {
            DeviceChannelDTO.ExtendInfo extendInfo = channel.getExtendInfo();
            if (extendInfo == null) {
                return null;
            }
            return trimToNull(subProfile ? extendInfo.getRtspSubUrl() : extendInfo.getRtspUrl());
        }
        return resolveDeviceRtspUrl(deviceId, subProfile);
    }

    private DeviceChannelDTO loadChannel(String deviceId, String channelId) {
        if (isBlank(channelId)) {
            return null;
        }
        try {
            DeviceChannelDTO channel = deviceChannelManager.getDtoByDeviceId(deviceId, channelId);
            if (channel != null) {
                log.debug("[rtsp.establish] 命中通道地址, deviceId={}, channelId={}", deviceId, channelId);
            }
            return channel;
        } catch (Exception e) {
            log.warn("[rtsp.establish] 读取通道配置失败, deviceId={}, channelId={}: {}", deviceId, channelId, e.getMessage());
            return null;
        }
    }

    /**
     * 读取设备扩展配置中的 RTSP 地址（无通道记录的存量设备走这里）。
     *
     * @param deviceId   设备 ID
     * @param subProfile true 取辅码流地址 {@code extendInfo.rtspSubUrl}，false 取主码流 {@code extendInfo.rtspUrl}
     * @return 规范化后的 rtsp:// 地址，未配置返回 null
     */
    private String resolveDeviceRtspUrl(String deviceId, boolean subProfile) {
        try {
            DeviceDTO device = deviceManager.getDtoByDeviceId(deviceId);
            if (device == null || device.getExtendInfo() == null) {
                return null;
            }
            return trimToNull(subProfile
                ? device.getExtendInfo().getRtspSubUrl()
                : device.getExtendInfo().getRtspUrl());
        } catch (Exception e) {
            log.warn("[rtsp.establish] 读取设备配置失败, deviceId={}: {}", deviceId, e.getMessage());
            return null;
        }
    }

    private static String trimToNull(String url) {
        return (url == null || url.isBlank()) ? null : url.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
