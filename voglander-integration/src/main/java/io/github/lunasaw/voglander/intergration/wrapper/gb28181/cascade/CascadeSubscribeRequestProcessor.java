package io.github.lunasaw.voglander.intergration.wrapper.gb28181.cascade;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.message.Response;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import gov.nist.javax.sip.message.SIPRequest;
import io.github.lunasaw.gb28181.common.entity.query.DeviceAlarmQuery;
import io.github.lunasaw.gb28181.common.entity.query.DeviceMobileQuery;
import io.github.lunasaw.gb28181.common.entity.query.DeviceQuery;
import io.github.lunasaw.gb28181.common.entity.xml.XmlBean;
import io.github.lunasaw.gbproxy.client.transmit.request.subscribe.SubscribeRequestProcessor;
import io.github.lunasaw.sip.common.enums.ContentTypeEnum;
import io.github.lunasaw.sip.common.transmit.ResponseCmd;
import io.github.lunasaw.sip.common.utils.SipRequestUtils;
import io.github.lunasaw.sip.common.utils.SipUtils;
import io.github.lunasaw.sip.common.utils.XmlUtils;
import io.github.lunasaw.voglander.manager.domaon.dto.cascade.CascadePlatformDTO;
import io.github.lunasaw.voglander.manager.manager.CascadePlatformManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 级联 SUBSCRIBE <b>请求</b>处理器：应答上级平台（如海康 iSecure Center）的通道目录订阅。
 *
 * <p><b>为什么不能复用框架处理器</b>：上级订阅要穿过两道方向校验，两道都过不去：
 * <ol>
 *   <li>{@link SubscribeRequestProcessor#process} 开头 {@code to.equals(getClientFromDevice().getUserId())}
 *       ——上级 {@code To} 是本端平台域 {@code 34020000002000000001}，而 {@code sip.client.clientId} 是
 *       {@code 34020000001320000011}，不等即 warn 后 return，<b>连 200 OK 都不回</b>；</li>
 *   <li>就算绕过第一道，{@code SubscribeCatalogQueryMessageHandler#handForEvt} 内部又要求
 *       {@code deviceSession.getFromDevice()} 的 userId 等于 {@code To} 头，而它取的是
 *       {@code getClientFromDevice()}（同样是设备侧身份），依旧不等 → return，
 *       <b>既不回 200 OK 也不发 {@code ClientSubscribeEvent}</b>。</li>
 * </ol>
 * 实测后果：上级每 60s 重传同一条 SUBSCRIBE（同 Call-ID / 同 CSeq / 同 branch），三次后放弃，
 * 目录永远为空。
 *
 * <p><b>本类的做法</b>：只认「from 属于 {@code tb_cascade_platform}」的订阅，收到后
 * <b>先回 200 OK（带 Expires）</b>消掉重传，再把订阅意图交给 {@link CascadeSubscribeHandler} 落库。
 * 设备侧发给本端的 SUBSCRIBE 不受影响。
 *
 * @author luna
 */
@Slf4j
@Getter
@Setter
@Component
public class CascadeSubscribeRequestProcessor extends SubscribeRequestProcessor {

    /** 与框架一致的请求 method 分派键 */
    public static final String METHOD_SUBSCRIBE = "SUBSCRIBE";

    /** 缺省订阅时长（秒）：上级未给 Expires 时按 GB28181 常用值回包，避免回 0 直接失效 */
    private static final int DEFAULT_EXPIRES = 3600;

    /** {@code Event: Catalog;expires=1800} 里的时长参数 */
    private static final Pattern EVENT_EXPIRES_PARAM = Pattern.compile("expires\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 框架 {@code SipProxyAutoConfig#registerRequestProcessors} 用 {@code getDeclaredField("method")}
     * 反射取分派键，只看本类声明的字段、不查父类；不重复声明会让它抛 {@code NoSuchFieldException}。
     */
    private String method = METHOD_SUBSCRIBE;

    private final CascadePlatformManager cascadePlatformManager;

    /**
     * 上级订阅接收器软注入：lab 模式（{@code voglander.protocol-lab.enabled=true}）下
     * {@link CascadeSubscribeHandler} 不装配，由 {@code LabSubscribeListener} 顶替。
     */
    private final ObjectProvider<CascadeSubscribeHandler> subscribeHandlerProvider;

    /** 目录快照推送器软注入：{@code sip.client.enabled=false} 时该 Bean 不存在。 */
    private final ObjectProvider<CascadeNotifyPublisher> notifyPublisherProvider;

    public CascadeSubscribeRequestProcessor(CascadePlatformManager cascadePlatformManager,
        ObjectProvider<CascadeSubscribeHandler> subscribeHandlerProvider,
        ObjectProvider<CascadeNotifyPublisher> notifyPublisherProvider) {
        this.cascadePlatformManager = cascadePlatformManager;
        this.subscribeHandlerProvider = subscribeHandlerProvider;
        this.notifyPublisherProvider = notifyPublisherProvider;
    }

    @Override
    public void process(RequestEvent evt) {
        process(evt, null);
    }

    @Override
    public void process(RequestEvent evt, ServerTransaction serverTransaction) {
        SIPRequest request = (SIPRequest)evt.getRequest();
        String fromUserId = SipUtils.getUserIdFromFromHeader(request);
        CascadePlatformDTO platform = resolvePlatform(fromUserId);
        if (platform == null) {
            /* 设备侧订阅照旧走框架默认处理器，本类不接管 */
            log.debug("收到非上级平台 SUBSCRIBE，跳过: from={}", fromUserId);
            return;
        }

        EventHeader eventHeader = (EventHeader)request.getHeader(EventHeader.NAME);
        String eventType = eventHeader != null ? eventHeader.getEventType() : null;
        Integer expires = resolveExpires(eventHeader, request);
        String charset = StringUtils.defaultIfBlank(platform.getCharset(), "GB2312");
        log.info("收到上级平台 SUBSCRIBE: from={}, event={}, expires={}, callId={}",
            fromUserId, eventType, expires, SipUtils.getCallId(request));

        responseOk(evt, expires);
        dispatch(platform.getPlatformId(), eventType, expires, request, charset);
    }

    /**
     * 先应答：SIP 事务要求 200 OK 及时返回，业务登记随后做，避免上级因超时重传。
     *
     * <p>{@code protected} 而非 private，是为了让单元测试替换掉真实的 SIP 回包（它依赖运行时
     * {@code SipProvider}），只验证「是否应答」与「应答了什么时长」。
     */
    protected void responseOk(RequestEvent evt, Integer expires) {
        try {
            ContentTypeHeader contentType = ContentTypeEnum.APPLICATION_XML.getContentTypeHeader();
            int value = expires != null && expires > 0 ? expires : DEFAULT_EXPIRES;
            ExpiresHeader expiresHeader = SipRequestUtils.createExpiresHeader(value);
            ResponseCmd.sendResponse(Response.OK, "OK", contentType, evt, expiresHeader);
            log.debug("上级 SUBSCRIBE 已应答 200 OK: expires={}", value);
        } catch (Exception e) {
            /* 回包失败不影响后续登记：上级会重传，下一次仍走本处理器 */
            log.error("上级 SUBSCRIBE 应答失败: {}", e.getMessage(), e);
        }
    }

    /** 把订阅意图（Catalog / Alarm / MobilePosition）交给业务侧登记到 {@code tb_cascade_subscribe}。 */
    private void dispatch(String platformId, String eventType, Integer expires, SIPRequest request, String charset) {
        CascadeSubscribeHandler handler = subscribeHandlerProvider.getIfAvailable();
        if (handler == null) {
            log.warn("级联订阅接收器未装配（lab 模式或 sip.client 关闭），本次订阅不入库: platformId={}, event={}",
                platformId, eventType);
            return;
        }
        XmlBean body = parseBody(request, eventType, charset);
        try {
            if (body instanceof DeviceAlarmQuery alarmQuery) {
                handler.onAlarmSubscribe(platformId, expires, alarmQuery);
            } else if (body instanceof DeviceMobileQuery mobileQuery) {
                handler.onMobilePositionSubscribe(platformId, expires, mobileQuery);
            } else if (body instanceof DeviceQuery deviceQuery) {
                handler.onCatalogSubscribe(platformId, expires, deviceQuery);
                pushCatalogSnapshot(platformId, expires, deviceQuery);
            } else {
                log.warn("无法识别的上级订阅类型，忽略登记: platformId={}, event={}", platformId, eventType);
            }
        } catch (Exception e) {
            log.error("级联订阅登记失败: platformId={}, event={}", platformId, eventType, e);
        }
    }

    /**
     * 订阅生效后立即推一份完整目录快照。
     *
     * <p>上级订阅目录后只认第一包 Catalog NOTIFY 建立目录基线，200 OK 本身不带任何通道；
     * 不推这一包，上级侧目录永远为空。退订（{@code expires<=0}）不推。
     *
     * <p>推送失败不影响已发出的 200 OK：上级下次订阅、或任意通道变更通知都会补上。
     */
    private void pushCatalogSnapshot(String platformId, Integer expires, DeviceQuery query) {
        if (expires == null || expires <= 0) {
            return;
        }
        CascadeNotifyPublisher publisher = notifyPublisherProvider.getIfAvailable();
        if (publisher == null) {
            log.warn("目录快照推送器未装配（sip.client 关闭），上级将收不到目录: platformId={}", platformId);
            return;
        }
        try {
            publisher.pushCatalogSnapshot(platformId, query != null ? query.getSn() : null);
        } catch (Exception e) {
            log.error("目录快照首推失败: platformId={}", platformId, e);
        }
    }

    /**
     * 解析订阅报文的消息体。上级订阅都带 MANSCDP XML（海康实测：
     * {@code <Query><CmdType>Catalog</CmdType><SN>11436</SN><DeviceID>…</DeviceID></Query>}）；
     * 少数实现只给 {@code Event} 头不给体，此时返回 null，由业务侧按「无 SN」登记。
     */
    private XmlBean parseBody(SIPRequest request, String eventType, String charset) {
        byte[] rawContent = request.getRawContent();
        if (rawContent == null || rawContent.length == 0) {
            log.debug("上级 SUBSCRIBE 无消息体: event={}", eventType);
            return null;
        }
        String type = eventType;
        if (StringUtils.isBlank(type)) {
            try {
                type = XmlUtils.getCmdType(new String(rawContent, Charset.forName(charset)));
            } catch (Exception e) {
                log.warn("上级 SUBSCRIBE 消息体无法识别: {}", e.getMessage());
                return null;
            }
        }
        try {
            if ("Alarm".equalsIgnoreCase(type)) {
                return SipUtils.getObj(charset, DeviceAlarmQuery.class, rawContent);
            }
            if ("MobilePosition".equalsIgnoreCase(type)) {
                return SipUtils.getObj(charset, DeviceMobileQuery.class, rawContent);
            }
            if ("Catalog".equalsIgnoreCase(type)) {
                return SipUtils.getObj(charset, DeviceQuery.class, rawContent);
            }
        } catch (Exception e) {
            log.warn("上级 SUBSCRIBE 消息体解析失败: event={}, err={}", type, e.getMessage());
        }
        return null;
    }

    /**
     * 解析订阅时长：标准写法是 {@code Expires} 头；个别上级把时长写在
     * {@code Event: Catalog;expires=1800} 参数里（jaip 的 {@code EventHeader} 不暴露该参数，
     * 只能从头上文本取）。两者都没有时返回 null，由 {@link #responseOk} 用默认值兜底。
     */
    private Integer resolveExpires(EventHeader eventHeader, SIPRequest request) {
        ExpiresHeader expiresHeader = (ExpiresHeader)request.getHeader(ExpiresHeader.NAME);
        if (expiresHeader != null) {
            return expiresHeader.getExpires();
        }
        if (eventHeader == null) {
            return null;
        }
        Matcher matcher = EVENT_EXPIRES_PARAM.matcher(eventHeader.toString());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Event 头里的 expires 参数非法: {}", eventHeader);
            }
        }
        return null;
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
}
