package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import javax.sip.message.Request;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.sip.common.transmit.SipSender;
import io.github.lunasaw.sip.common.transmit.event.Event;
import io.github.lunasaw.sip.common.transmit.request.SipRequestBuilderFactory;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 以「级联上级平台配置里的本端身份」发送 MANSCDP MESSAGE。
 *
 * <p>框架的 {@code ClientListenerAdapter} 回包时用 {@code sip.client.clientId}（设备侧客户端编码）当 From，
 * 与上级在 {@code tb_cascade_platform} 里登记的本端平台编码 {@code local_client_id} 不是同一个，
 * 身份不符的应答会被上级丢弃。凡是以「平台」而非「设备」身份发出的 MESSAGE（查询应答、
 * 语音广播应答、目录分片）都必须走本类。
 *
 * <p>Call-ID 约定：<b>应答</b>必须沿用上级请求的 Call-ID（{@link #sendByPlatformId} 传入
 * {@link io.github.lunasaw.sip.common.context.SipTransactionContext#getCurrentCallId()}）；
 * <b>主动通知</b>或后续分片用 {@code null} 让本类新建，避免同一 Call-ID 承载多条无事务关联的报文。
 *
 * @author luna
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeUpperMessenger {

    private final CascadePlatformManager cascadePlatformManager;
    private final CascadeDeviceSupplier  cascadeDeviceSupplier;

    /**
     * 按平台编码发送一条 MESSAGE。
     *
     * <p>方法名不复用 {@code send}：首个参数同时存在 {@link String} 与
     * {@link CascadePlatformDTO} 两种重载时，Mockito 的裸 {@code any()} 会因重载二义编译不过。
     *
     * @param platformId 上级平台编码（{@code tb_cascade_platform.platform_id}）
     * @param xml        MANSCDP 报文
     * @param callId     指定 Call-ID（应答沿用入站）或 {@code null} 表示新建
     * @param okEvent    收到 2xx 时的回调（分片串行用），可为 {@code null}
     * @return 实际使用的 Call-ID；平台不存在 / 地址缺失 / 发送异常时返回 {@code null}
     */
    public String sendByPlatformId(String platformId, String xml, String callId, Event okEvent) {
        CascadePlatformDTO platform = resolve(platformId);
        if (platform == null) {
            return null;
        }
        return send(platform, xml, callId, okEvent);
    }

    /**
     * 发送重载：调用方已持有平台配置时避免二次查库。
     *
     * @return 实际使用的 Call-ID；不可发送时返回 {@code null}
     */
    public String send(CascadePlatformDTO platform, String xml, String callId, Event okEvent) {
        if (platform == null || StringUtils.isBlank(xml)) {
            return null;
        }
        FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
        ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
        if (from == null || StringUtils.isBlank(from.getIp()) || to == null || StringUtils.isBlank(to.getIp())) {
            log.warn("级联应答跳过，平台地址不完整: platformId={}, localIp={}, platformIp={}",
                platform.getPlatformId(), platform.getLocalIp(), platform.getPlatformIp());
            return null;
        }
        /* 不用 defaultIfBlank：它的默认值是急求值的，指定 Call-ID 时也会白生成一个 */
        String actualCallId = StringUtils.isNotBlank(callId) ? callId : SipRequestUtils.getNewCallId();
        try {
            Request request = SipRequestBuilderFactory.createMessageRequest(from, to, xml, actualCallId);
            SipSender.transmitRequest(from.getIp(), request, null, okEvent);
            log.debug("级联 MESSAGE 已发送: platformId={}, callId={}, 报文长度={}",
                platform.getPlatformId(), actualCallId, xml.length());
            return actualCallId;
        } catch (Exception e) {
            log.error("级联 MESSAGE 发送失败: platformId={}, callId={}", platform.getPlatformId(), actualCallId, e);
            return null;
        }
    }

    /** 查平台配置；查库异常按「不可发送」处理，避免一条应答把整轮消息处理打断。 */
    private CascadePlatformDTO resolve(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return null;
        }
        try {
            CascadePlatformDTO platform = cascadePlatformManager.getByPlatformId(platformId);
            if (platform == null) {
                log.warn("级联应答跳过，找不到平台配置: platformId={}", platformId);
            }
            return platform;
        } catch (Exception e) {
            log.warn("查询级联平台失败: platformId={}, err={}", platformId, e.getMessage());
            return null;
        }
    }
}
