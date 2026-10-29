package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import java.nio.charset.Charset;
import java.security.MessageDigest;

import javax.sip.ResponseEvent;
import javax.sip.address.URI;
import javax.sip.address.SipURI;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import gov.nist.javax.sip.message.SIPResponse;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterChallengeEvent;
import io.github.lunasaw.gbproxy.client.eventbus.event.ClientRegisterFailureEvent;
import io.github.lunasaw.gbproxy.client.transmit.response.register.ClientRegisterResponseProcessor;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.ToDevice;
import io.github.lunasaw.sip.common.transmit.SipSender;
import io.github.lunasaw.sip.common.transmit.request.SipRequestBuilderFactory;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 级联注册的 401 重认证处理器。
 *
 * <p>框架自带的 {@link ClientRegisterResponseProcessor} 在收到 401 后固定用
 * {@code deviceSupplier.getClientFromDevice()}（全局客户端身份：设备编码 + 客户端密码）重认证，
 * 且摘要 username 取该身份的 userId。级联上级（如海康 iSecure Center）登记的是
 * <b>本端客户端ID + 平台密码</b>，且摘要 username 必须等于上级填写的「鉴权登录名」，
 * 因此框架重认证必然 403，表现为我方只看到注册超时。
 *
 * <p>本处理器按上级平台响应 To 头里的平台编码定位 tb_cascade_platform 记录，然后
 * <ul>
 *   <li>From/Contact 用平台配置的 localClientId（国标要求的本端编码）；</li>
 *   <li>摘要 username 用平台配置的 username（即上级「鉴权登录名」），为空时回落到 localClientId；</li>
 *   <li>摘要密码用平台配置的 password。</li>
 * </ul>
 * 与参考实现 CSS（{@code gb28181_register.go#createAuthString}）的做法一致：
 * 摘要凭据来自平台配置，而不是全局客户端身份。
 *
 * <p>同时补上 403 的失败上报：框架不把 403 当作失败事件，注册状态只能等超时才转失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CascadeRegisterAuthProcessor extends ClientRegisterResponseProcessor {

    private static final String DIGEST_ALGORITHM = "MD5";
    private static final String DEFAULT_CHARSET   = "GB2312";
    private static final int    DEFAULT_EXPIRES   = 3600;

    private final CascadePlatformManager  cascadePlatformManager;
    private final CascadeDeviceSupplier   cascadeDeviceSupplier;
    private final CascadePeerAliveTracker peerAliveTracker;
    private final CascadeUnregisterService cascadeUnregisterService;

    @Override
    public void process(ResponseEvent evt) {
        SIPResponse response = getResponse(evt);
        if (response == null) {
            super.process(evt);
            return;
        }

        int status = response.getStatusCode();
        String toUserId = resolveToUserId(response);
        /* 任意 REGISTER 响应都证明上级信令可达，登记存活基线（非心跳类响应） */
        if (toUserId != null) {
            peerAliveTracker.markResponse(toUserId, false);
        }
        if (status != Response.UNAUTHORIZED && status != Response.FORBIDDEN) {
            super.process(evt);
            return;
        }

        CascadePlatformDTO platform = toUserId == null ? null : cascadePlatformManager.getByPlatformId(toUserId);
        if (platform == null) {
            super.process(evt);
            return;
        }

        String callId = getCallId(evt);
        if (status == Response.UNAUTHORIZED) {
            if (getPublisher() != null) {
                getPublisher().publishEvent(new ClientRegisterChallengeEvent(this, toUserId, callId));
            }
            if (reAuthenticate(response, platform, callId)) {
                return;
            }
            super.process(evt);
            return;
        }

        /* 注销过程中的 403 不必当成"注册失败"上报，平台本来就已停用 */
        if (cascadeUnregisterService.isUnregistering(platform.getPlatformId())) {
            log.warn("级联注销被拒绝(403)，按本地停用处理: platformId={}, target={}:{}",
                platform.getPlatformId(), platform.getPlatformIp(), platform.getPlatformPort());
            return;
        }

        /* 403：框架不发失败事件，这里显式上报，让 tbl 状态立即转 FAILED 并给出排查提示 */
        log.warn("级联注册被拒绝(403): platformId={}, 鉴权用户名={}, localClientId={}, target={}:{}. "
                + "请核对上级「鉴权登录名/鉴权密码」与本端平台配置是否一致",
            platform.getPlatformId(), resolveAuthUsername(platform), platform.getLocalClientId(),
            platform.getPlatformIp(), platform.getPlatformPort());
        if (getPublisher() != null) {
            getPublisher().publishEvent(new ClientRegisterFailureEvent(this, toUserId, status));
        }
    }

    /**
     * 用平台自身身份回送带摘要的 REGISTER。
     *
     * @param response 401 响应，提供 realm/nonce 与原 CSeq
     * @param platform 上级平台配置
     * @param callId   原 REGISTER 的 Call-ID（重认证必须沿用）
     * @return 已发出时返回 true；缺挑战或发送异常返回 false，交由框架兜底
     */
    private boolean reAuthenticate(SIPResponse response, CascadePlatformDTO platform, String callId) {
        WWWAuthenticateHeader challenge =
            (WWWAuthenticateHeader) response.getHeader(WWWAuthenticateHeader.NAME);
        if (challenge == null || StringUtils.isBlank(challenge.getNonce())) {
            log.warn("级联 401 响应缺少 WWW-Authenticate 挑战头: platformId={}", platform.getPlatformId());
            return false;
        }

        try {
            FromDevice from = cascadeDeviceSupplier.buildFromDevice(platform);
            ToDevice   to   = cascadeDeviceSupplier.buildToDevice(platform);
            /* 注销流程中（Expires=0）同样要走鉴权，否则上级会保留上一次登记的 Contact */
            boolean unregistering = cascadeUnregisterService.isUnregistering(platform.getPlatformId());
            int expires = unregistering ? 0
                : (platform.getRegisterExpires() != null ? platform.getRegisterExpires() : DEFAULT_EXPIRES);

            Request request = SipRequestBuilderFactory.createRegisterRequest(from, to, expires, callId);
            String  auth    = buildAuthorization(request, challenge, platform);
            request.setHeader(SipRequestUtils.createHeader("Authorization", auth));
            /* 重认证必须沿用 Call-ID 且 CSeq 递增，否则上级会当成重传丢弃 */
            request.setHeader(SipRequestUtils.createCSeqHeader(response.getCSeq().getSeqNumber() + 1, Request.REGISTER));

            SipSender.transmitRequest(from.getIp(), request);
            log.info("级联 401 重认证已发送: platformId={}, 鉴权用户名={}, realm={}, callId={}, cseq={}, expires={}{}",
                platform.getPlatformId(), resolveAuthUsername(platform), challenge.getRealm(), callId,
                response.getCSeq().getSeqNumber() + 1, expires, unregistering ? "(注销)" : "");
            return true;
        } catch (Exception e) {
            log.error("级联 401 重认证发送失败: platformId={}, callId={}, err={}",
                platform.getPlatformId(), callId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 按 RFC 2617 拼 Digest 摘要。海康挑战不带 qop，走 MD5(HA1:nonce:HA2) 分支；
     * 带 qop=auth 时补 nc/cnonce，避免个别上级启用 qop 后失效。
     */
    private String buildAuthorization(Request request, WWWAuthenticateHeader challenge, CascadePlatformDTO platform) {
        String username = resolveAuthUsername(platform);
        String realm    = StringUtils.defaultString(challenge.getRealm());
        String nonce    = challenge.getNonce();
        String uri      = request.getRequestURI().toString();
        String charset  = StringUtils.defaultIfBlank(platform.getCharset(), DEFAULT_CHARSET);

        String ha1      = md5(username + ":" + realm + ":" + StringUtils.defaultString(platform.getPassword()), charset);
        String ha2      = md5("REGISTER:" + uri, charset);

        String qop = challenge.getQop();
        String response;
        StringBuilder auth = new StringBuilder("Digest username=\"").append(username).append('"')
            .append(", realm=\"").append(realm).append('"')
            .append(", nonce=\"").append(nonce).append('"')
            .append(", uri=\"").append(uri).append('"');

        if (StringUtils.isNotBlank(qop) && !"none".equalsIgnoreCase(qop)) {
            String nc     = "00000001";
            String cnonce = md5(nonce + username, charset).substring(0, 16);
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2, charset);
            auth.append(", response=\"").append(response).append('"')
                .append(", qop=auth, nc=").append(nc)
                .append(", cnonce=\"").append(cnonce).append('"');
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2, charset);
            auth.append(", response=\"").append(response).append('"');
        }

        auth.append(", algorithm=").append(DIGEST_ALGORITHM);
        String opaque = challenge.getOpaque();
        if (StringUtils.isNotBlank(opaque)) {
            auth.append(", opaque=\"").append(opaque).append('"');
        }
        return auth.toString();
    }

    /** 摘要 username：上级「鉴权登录名」，未配置时回落到本端客户端ID（国标默认同值）。 */
    private String resolveAuthUsername(CascadePlatformDTO platform) {
        return StringUtils.isNotBlank(platform.getUsername())
            ? platform.getUsername() : platform.getLocalClientId();
    }

    /** 从响应 To 头取上级平台编码，用于定位平台配置。 */
    private String resolveToUserId(SIPResponse response) {
        if (response.getTo() == null || response.getTo().getAddress() == null) {
            return null;
        }
        URI uri = response.getTo().getAddress().getURI();
        return uri instanceof SipURI sipUri ? sipUri.getUser() : null;
    }

    private String md5(String text, String charset) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(Charset.forName(charset)));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 摘要计算失败: " + e.getMessage(), e);
        }
    }
}