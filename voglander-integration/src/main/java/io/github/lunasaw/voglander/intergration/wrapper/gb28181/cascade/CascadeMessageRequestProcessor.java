package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import gov.nist.javax.sip.message.SIPRequest;
import io.github.lunasaw.gb28181.common.entity.notify.DeviceBroadcastNotify;
import io.github.lunasaw.gbproxy.client.transmit.request.message.ClientMessageRequestProcessor;
import io.github.lunasaw.sip.common.entity.FromDevice;
import io.github.lunasaw.sip.common.entity.RemoteAddressInfo;
import io.github.lunasaw.sip.common.transmit.ResponseCmd;
import io.github.lunasaw.sip.common.transmit.event.message.MessageHandler;
import io.github.lunasaw.sip.common.transmit.event.request.SipRequestProcessorAbstract;
import io.github.lunasaw.sip.common.utils.SipUtils;
import io.github.lunasaw.sip.common.utils.XmlUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 级联 MESSAGE <b>请求</b>处理器：把上级平台发来的 MESSAGE（Catalog 查询等）真正交给消息处理器链。
 *
 * <p><b>为什么需要本类</b>：框架的 {@link ClientMessageRequestProcessor} 在 {@code process} 开头做了一道
 * 客户端方向校验——
 * <pre>to.equals(clientDeviceSupplier.getClientFromDevice().getUserId())</pre>
 * 不成立就 <em>warn 后直接 return</em>，既不回 200 OK，也不会把请求转给
 * {@code SipRequestProcessorAbstract#doMessageHandForEvt}，于是 {@code QueryListener}（含
 * {@link CascadeQueryHandler}）永远收不到回调。
 *
 * <p>这道校验对「我作为客户端主动发 MESSAGE 订阅别人」是对的，但本平台同时是<b>被查询方</b>：
 * 上级的 {@code To} 是本端平台域（{@code tb_cascade_platform.local_client_id}，如
 * {@code 34020000002000000001}），而 {@code sip.client.clientId} 是设备侧客户端身份
 * （{@code 34020000001320000011}），两者天然不等。实测日志：
 * <pre>
 * WARN ClientMessageRequestProcessor - MESSAGE请求的目标用户ID与客户端不匹配:
 *      from=37000020002000000100, to=34020000002000000001, clientFromDevice=FromDevice(...)
 * </pre>
 *
 * <p><b>本类的做法</b>：只校验收到的 MESSAGE 是否来自已知的上级平台（{@code tb_cascade_platform}），
 * 是则直接交给 {@code doMessageHandForEvt} 完成「按 CmdType 找处理器 → 回 200 OK → 回调
 * QueryListener」；否则原样放弃，把设备侧的 MESSAGE 留给设备侧处理器，互不干扰。
 *
 * @author luna
 */
@Slf4j
@Getter
@Setter
@Component
public class CascadeMessageRequestProcessor extends ClientMessageRequestProcessor {

    /** 与框架一致的请求 method 分派键 */
    public static final String METHOD_MESSAGE = "MESSAGE";

    /**
     * 框架 {@code SipProxyAutoConfig#registerRequestProcessors} 用 {@code getDeclaredField("method")}
     * 反射取分派键，只看本类声明的字段、不查父类；不重复声明会让它抛 {@code NoSuchFieldException}。
     */
    private String method = METHOD_MESSAGE;

    /** 订阅/通知类报文的根元素（语音广播走 Notify） */
    private static final String ROOT_NOTIFY          = "Notify";

    /** 语音广播通知的 CmdType（§9.12） */
    private static final String CMD_BROADCAST        = "Broadcast";

    /** 上级报文未声明字符集时的默认值（国标信令宜用 GB2312） */
    private static final String DEFAULT_CHARSET      = "GB2312";

    private final CascadePlatformManager cascadePlatformManager;
    private final CascadeBroadcastHandler cascadeBroadcastHandler;

    public CascadeMessageRequestProcessor(CascadePlatformManager cascadePlatformManager,
        CascadeBroadcastHandler cascadeBroadcastHandler) {
        this.cascadePlatformManager = cascadePlatformManager;
        this.cascadeBroadcastHandler = cascadeBroadcastHandler;
    }

    @Override
    public void process(RequestEvent evt) {
        process(evt, null);
    }

    /**
     * 监听器会把预创建的事务透传进来，这里转交给 {@code doMessageHandForEvt}，
     * 让 {@code responseAck} 复用同一事务回 200 OK。
     */
    @Override
    public void process(RequestEvent evt, ServerTransaction serverTransaction) {
        SIPRequest request = (SIPRequest)evt.getRequest();
        String fromUserId = SipUtils.getUserIdFromFromHeader(request);
        CascadePlatformDTO platform = resolvePlatform(fromUserId);
        if (platform == null) {
            /* 不是上级平台发来的（例如设备侧自身的 MESSAGE），交给设备侧处理器，保持原有行为 */
            log.debug("收到非上级平台 MESSAGE，跳过: from={}", fromUserId);
            return;
        }
        log.info("收到上级平台 MESSAGE: from={}, to={}, callId={}",
            fromUserId, SipUtils.getUserIdFromToHeader(request), SipUtils.getCallId(request));

        String xml      = decodeBody(request, platform);
        String rootType = safeRootType(xml);
        String cmdType  = safeCmdType(xml);
        if (!hasHandler(request.getMethod(), rootType, cmdType)) {
            /* 框架找不到处理器时既不回 200 OK 也不处理，上级会按 Timer E/F 一直重传；这里补一个 200 OK 收尾 */
            log.warn("上级 MESSAGE 无对应处理器，仅回 200 OK: from={}, method={}, rootType={}, cmdType={}",
                fromUserId, request.getMethod(), rootType, cmdType);
            ResponseCmd.sendResponse(Response.OK, evt, serverTransaction);
            return;
        }
        try {
            doMessageHandForEvt(evt, buildUpperFromDevice(platform, request), serverTransaction);
        } catch (Exception e) {
            log.error("处理上级平台 MESSAGE 失败: from={}", fromUserId, e);
        }

        /* 语音广播：200 OK 已由框架回出，国标还要求单独回一条 Response/Broadcast（NotifyListener 返回 void，回不了） */
        if (ROOT_NOTIFY.equals(rootType) && CMD_BROADCAST.equals(cmdType)) {
            handleBroadcast(platform, xml);
        }
    }

    /** SipRequestProcessorAbstract 的分派表：按 rootType → method_CmdType 找处理器。 */
    private boolean hasHandler(String method, String rootType, String cmdType) {
        if (StringUtils.isAnyBlank(rootType, cmdType, method)) {
            return false;
        }
        Map<String, MessageHandler> handlerMap = SipRequestProcessorAbstract.MESSAGE_HANDLER_CMD_MAP.get(rootType);
        return handlerMap != null && handlerMap.get(method + "_" + cmdType) != null;
    }

    /** 按平台字符集解码报文正文（正文非 UTF-8 时按 UTF-8 解会把中文名解成乱码）。 */
    private String decodeBody(SIPRequest request, CascadePlatformDTO platform) {
        byte[] raw = request.getRawContent();
        if (raw == null) {
            return null;
        }
        String charsetName = StringUtils.defaultIfBlank(platform.getCharset(), DEFAULT_CHARSET);
        try {
            return new String(raw, Charset.forName(charsetName));
        } catch (Exception e) {
            log.warn("报文解码失败，按 UTF-8 重试: charset={}, err={}", charsetName, e.getMessage());
            return new String(raw, Charset.forName("UTF-8"));
        }
    }

    /** 根元素名；解析失败返回 null（由调用方按「无处理器」处理并回 200 OK 收尾）。 */
    private String safeRootType(String xml) {
        if (StringUtils.isBlank(xml)) {
            return null;
        }
        try {
            return XmlUtils.getRootType(xml);
        } catch (Exception e) {
            log.warn("报文根元素解析失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeCmdType(String xml) {
        if (StringUtils.isBlank(xml)) {
            return null;
        }
        try {
            return XmlUtils.getCmdType(xml);
        } catch (Exception e) {
            log.warn("报文 CmdType 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析广播通知并回应答；解析失败只记日志，不影响已经回出的 200 OK。 */
    private void handleBroadcast(CascadePlatformDTO platform, String xml) {
        try {
            Object parsed = XmlUtils.parseObj(xml, DeviceBroadcastNotify.class);
            if (parsed instanceof DeviceBroadcastNotify notify) {
                cascadeBroadcastHandler.handle(platform, notify);
            } else {
                log.warn("语音广播通知解析结果类型异常: platformId={}, type={}",
                    platform.getPlatformId(), parsed == null ? "null" : parsed.getClass().getName());
            }
        } catch (Exception e) {
            log.warn("语音广播通知解析失败: platformId={}, err={}", platform.getPlatformId(), e.getMessage());
        }
    }

    /** from 是否为 {@code tb_cascade_platform} 中的平台编码（空值直接判否）。 */
    private CascadePlatformDTO resolvePlatform(String platformId) {
        if (StringUtils.isBlank(platformId)) {
            return null;
        }
        try {
            return cascadePlatformManager.getByPlatformId(platformId);
        } catch (Exception e) {
            log.warn("查询级联平台失败，按非级联处理: platformId={}, err={}", platformId, e.getMessage());
            return null;
        }
    }

    /**
     * 按上级平台配置构造请求侧设备对象。
     *
     * <p>框架在 {@code processMessageRequest} 里写的是 {@code Optional.of(fromDevice).map(Device::getCharset)}
     * ——{@code Optional.of(null)} 会直接抛 NPE，所以这里必须给一个非 null 实例；它只用于取字符集
     * （上级报文常见 {@code <?xml version="1.0" encoding="GB2312"?>}）与打日志，不参与鉴权。
     */
    private FromDevice buildUpperFromDevice(CascadePlatformDTO platform, Request request) {
        FromDevice from = new FromDevice();
        from.setUserId(platform.getPlatformId());
        RemoteAddressInfo remote = SipUtils.getRemoteAddressFromRequest((SIPRequest)request);
        if (remote != null) {
            from.setIp(remote.getIp());
            from.setPort(remote.getPort() != null ? remote.getPort() : 5060);
        }
        from.setCharset(StringUtils.defaultIfBlank(platform.getCharset(), "GB2312"));
        return from;
    }
}
