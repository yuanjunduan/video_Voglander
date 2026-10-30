package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.lunasaw.gb28181.common.entity.notify.DeviceBroadcastNotify;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadeChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 语音广播通知应答（§9.12 / A.2.6 e）。
 *
 * <p>国标流程：上级（语音输入设备侧）发 {@code Notify/Broadcast(SourceID, TargetID)} →
 * 语音流接收者回 200 OK → <b>再单独发一条</b>
 * {@code <Response><CmdType>Broadcast</CmdType><SN/><DeviceID/><Result/></Response>}。
 *
 * <p>框架的 {@code NotifyListener#onBroadcastNotify} 返回 {@code void}（只发布 Spring 事件），
 * 结构上无法回这条应答，所以由 {@link CascadeMessageRequestProcessor} 在处理完 MESSAGE 后
 * 交给本类补发。
 *
 * <p>本类只做「应答 + 是否接受」的判定：语音流的建立（本端作为语音输出设备向语音输入设备
 * 主动 INVITE、{@code m=audio ... RTP/AVP 8} + {@code a=rtpmap:8 PCMA/8000}）涉及音频桥接，
 * 需与真实语音设备联调，另行排期。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeBroadcastHandler {

    private final CascadeChannelManager cascadeChannelManager;
    private final CascadeUpperMessenger cascadeUpperMessenger;

    /**
     * 判定并向发出通知的上级回一条语音广播应答。
     *
     * @param platform 上级平台配置（已由 MESSAGE 处理器按 From 头查出）
     * @param notify   解析后的广播通知
     * @return 是否已发出应答（平台地址缺失时返回 false）
     */
    public boolean handle(CascadePlatformDTO platform, DeviceBroadcastNotify notify) {
        if (platform == null || notify == null) {
            return false;
        }
        String targetId = StringUtils.trimToEmpty(notify.getTargetId());
        String sn       = notify.getSn();
        boolean accepted = isLocalTarget(platform, targetId);
        if (!accepted) {
            log.warn("语音广播目标不是本级通道，回 ERROR: platformId={}, targetId={}, sourceId={}",
                platform.getPlatformId(), targetId, notify.getSourceId());
        } else {
            log.info("收到语音广播通知，回 OK: platformId={}, sourceId={}, targetId={}, sn={}",
                platform.getPlatformId(), notify.getSourceId(), targetId, sn);
        }
        String xml = CascadeManscdpXml.broadcastResponse(targetId, sn, accepted ? "OK" : "ERROR");
        /* 应答用独立 Call-ID：上级的 Notify 事务已经由 200 OK 收尾，这条 Response 是新的独立事务 */
        String callId = cascadeUpperMessenger.send(platform, xml, null, null);
        return callId != null;
    }

    /**
     * TargetID 是否指向本级：本级平台编码（整个平台作为语音输出设备）或已登记且启用的级联通道。
     *
     * <p>不按编码前缀/长度推断——级联通道编码由上级分配，规则不由本端决定。
     */
    private boolean isLocalTarget(CascadePlatformDTO platform, String targetId) {
        if (StringUtils.isBlank(targetId)) {
            return false;
        }
        if (targetId.equals(StringUtils.trimToEmpty(platform.getLocalClientId()))) {
            return true;
        }
        try {
            return cascadeChannelManager.getByPlatformAndCascadeChannelId(platform.getPlatformId(), targetId) != null;
        } catch (Exception e) {
            /* 查库异常按「不认」处理：宁可回 ERROR 让上级报错，也不要对未知目标声称 OK */
            log.warn("语音广播目标校验异常: platformId={}, targetId={}, err={}",
                platform.getPlatformId(), targetId, e.getMessage());
            return false;
        }
    }
}
