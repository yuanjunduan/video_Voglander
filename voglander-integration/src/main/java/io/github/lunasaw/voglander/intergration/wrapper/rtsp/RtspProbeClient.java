package io.github.lunasaw.voglander.intergration.wrapper.rtsp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.intergration.wrapper.rtsp.dto.RtspProbeResult;
import io.github.lunasaw.voglander.intergration.wrapper.rtsp.support.DigestChallenge;
import io.github.lunasaw.voglander.intergration.wrapper.rtsp.support.RtspResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * RTSP 地址连通性探测：TCP 建连 + OPTIONS 握手；URL 带账号密码时再走一遍 Digest 挑战，
 * 把“要求鉴权”细化成“密码对 / 密码错”。
 *
 * <p>
 * 为什么只做 OPTIONS：设备不同厂商对 DESCRIBE 的鉴权方式、通道号拼法差异极大，
 * 深探会把「地址能通、只是我们拼错了通道」误判成「设备不可达」。OPTIONS 是最小公约数——
 * 2xx 说明地址可用；401/403 说明地址对但缺凭据；404/454 说明设备在但路径错。
 * </p>
 *
 * <p>
 * 挑战与重发**必须复用同一条 socket**：大华等设备把 Digest nonce 绑在 TCP 连接上，
 * 换连接重放旧 nonce 一律 401（实测：跨连接 401，同连接 200 OK）。因此本类不再“发一条就读状态行收工”，
 * 而是按条消费完整响应头后再发下一条。
 * </p>
 *
 * <p>
 * 不套 Wrapper Service + ResultDTO：本类无可替换实现、无外部配置，也没挂熔断
 * （探测目标是用户随手输入的任意 IP，熔断会把一次失败扩散成对其它设备的误判）。
 * 参数非法直接抛 {@link IllegalArgumentException}，由 Web 层翻译成 400 语义。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
@Slf4j
@Component
public class RtspProbeClient {

    /**
     * RTSP 默认端口
     */
    public static final int                   DEFAULT_PORT       = 554;

    /**
     * 默认建连/读超时（毫秒）
     */
    public static final int                   DEFAULT_TIMEOUT_MS = 3000;

    /**
     * 探测用的 RTSP 方法：OPTIONS 无副作用、不需要 SDP，是各厂商的最小公约数
     */
    private static final String               METHOD_OPTIONS     = "OPTIONS";

    private static final String               USER_AGENT         = "VoglanderProbe/1.0";

    /**
     * 匿名探测的 CSeq
     */
    private static final int                  CSEQ_FIRST         = 1;

    /**
     * 带 Digest 重发的 CSeq（递增，与 ffmpeg 行为一致）
     */
    private static final int                  CSEQ_RETRY         = 2;

    /**
     * 探测给定 RTSP 地址（默认 3s 超时）。
     *
     * @param rtspUrl 形如 {@code rtsp://user:pass@192.168.1.64:554/Streaming/Channels/101}
     * @return 探测结果；网络层失败不抛异常，以 {@code reachable=false} 返回
     * @throws IllegalArgumentException 地址为空、scheme 不是 rtsp、或缺 host
     */
    public RtspProbeResult probe(String rtspUrl) {
        return probe(rtspUrl, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 探测给定 RTSP 地址（指定超时，测试用）。
     */
    public RtspProbeResult probe(String rtspUrl, int timeoutMs) {
        URI uri = parse(rtspUrl);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        String target = host + ":" + port;
        String requestUri = requestUriOf(uri, host, port);
        Credentials credentials = Credentials.of(uri);

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            writeOptions(out, requestUri, target, CSEQ_FIRST, null);
            RtspResponse first = RtspResponse.read(reader);
            if (first == null) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("[rtsp-probe] 无响应 target={} elapsed={}ms", target, elapsed);
                return RtspProbeResult.notRtsp(target, elapsed, null);
            }
            if (!first.isRtsp()) {
                long elapsed = System.currentTimeMillis() - start;
                log.warn("[rtsp-probe] 非 RTSP 响应 target={} elapsed={}ms firstLine={}",
                    target, elapsed, first.getStatusLine());
                return RtspProbeResult.notRtsp(target, elapsed, first.getStatusLine());
            }

            RtspProbeResult result = resolveChallenge(out, reader, first, requestUri, target, credentials, start);
            log.info("[rtsp-probe] target={} reachable={} status={} authRequired={} authenticated={} elapsed={}ms",
                target, result.isReachable(), result.getStatusCode(), result.isAuthRequired(),
                result.isAuthenticated(), result.getElapsedMs());
            return result;
        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[rtsp-probe] 不可达 target={} elapsed={}ms err={}", target, elapsed, e.getMessage());
            return RtspProbeResult.unreachable(target, elapsed, e);
        }
    }

    /**
     * 解析并校验 URL：只接受 rtsp scheme（大小写不敏感）。
     */
    private URI parse(String rtspUrl) {
        if (rtspUrl == null || rtspUrl.isBlank()) {
            throw new IllegalArgumentException("RTSP 地址不能为空");
        }
        URI uri;
        try {
            uri = new URI(rtspUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("RTSP 地址格式非法：" + e.getReason());
        }
        if (uri.getScheme() == null || !"rtsp".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("只支持 rtsp:// 开头的地址，当前为：" + uri.getScheme());
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("RTSP 地址缺少主机部分");
        }
        return uri;
    }

    /**
     * 构造请求行里的 URI：去掉 userinfo（RTSP 规范不带账号密码），保留 path + query。
     */
    private String requestUriOf(URI uri, String host, int port) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "rtsp://" + host + ":" + port + path + query;
    }

    /**
     * 首次响应要求鉴权时，用 URL 里的账号密码在同一连接内走一遍 Digest 挑战。
     *
     * <p>
     * 分支只有四种：不需要鉴权（原样返回）、设备要鉴权但地址没带凭据、设备用的是暂不支持的方案、
     * 带凭据重发（被接受 / 被拒绝）。任何一种都不影响 {@code reachable}——只要能收到 RTSP 响应，
     * “设备在不在”就已经有答案了。
     * </p>
     */
    private RtspProbeResult resolveChallenge(OutputStream out, BufferedReader reader, RtspResponse first,
                                             String requestUri, String target, Credentials credentials,
                                             long start) throws IOException {
        if (!first.isAuthChallenge()) {
            long elapsed = System.currentTimeMillis() - start;
            return RtspProbeResult.reachable(target, elapsed, first.getStatusCode(), first.getReason());
        }
        if (credentials == null) {
            long elapsed = System.currentTimeMillis() - start;
            return RtspProbeResult.challengeWithoutCredential(target, elapsed, first.getStatusCode(),
                first.getReason());
        }

        String challengeHeader = first.firstHeader("WWW-Authenticate");
        DigestChallenge challenge = DigestChallenge.parse(challengeHeader);
        String authorization = challenge == null
            ? null
            : challenge.authorization(METHOD_OPTIONS, requestUri, credentials.user, credentials.password);
        if (authorization == null) {
            long elapsed = System.currentTimeMillis() - start;
            return RtspProbeResult.challengeUnsupported(target, elapsed, first.getStatusCode(), first.getReason(),
                schemeOf(challengeHeader));
        }

        try {
            writeOptions(out, requestUri, target, CSEQ_RETRY, authorization);
            RtspResponse second = RtspResponse.read(reader);
            long elapsed = System.currentTimeMillis() - start;
            if (second == null) {
                return RtspProbeResult.credentialRejected(target, elapsed, first.getStatusCode(), first.getReason());
            }
            if (!second.isRtsp()) {
                return RtspProbeResult.notRtsp(target, elapsed, second.getStatusLine());
            }
            return second.isSuccess()
                ? RtspProbeResult.credentialAccepted(target, elapsed, second.getStatusCode(), second.getReason())
                : RtspProbeResult.credentialRejected(target, elapsed, second.getStatusCode(), second.getReason());
        } catch (IOException e) {
            // 重发阶段断开不代表设备不可达：首次响应已经证明它在，只能判“凭据未通过”
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[rtsp-probe] 鉴权重发失败 target={} elapsed={}ms err={}", target, elapsed, e.getMessage());
            return RtspProbeResult.credentialRejected(target, elapsed, first.getStatusCode(), first.getReason());
        }
    }

    /**
     * 取认证方案名（Digest / Basic / …），仅用于文案；取不到返回 {@code null}。
     */
    private static String schemeOf(String challengeHeader) {
        if (challengeHeader == null || challengeHeader.isBlank()) {
            return null;
        }
        String trimmed = challengeHeader.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * 发一条 OPTIONS；{@code authorization} 为空是匿名探测，非空是 Digest 重发。
     */
    private void writeOptions(OutputStream out, String requestUri, String target, int cseq, String authorization)
        throws IOException {
        StringBuilder request = new StringBuilder()
            .append(METHOD_OPTIONS).append(' ').append(requestUri).append(" RTSP/1.0\r\n")
            .append("CSeq: ").append(cseq).append("\r\n")
            .append("User-Agent: ").append(USER_AGENT).append("\r\n")
            .append("Host: ").append(target).append("\r\n");
        if (authorization != null) {
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        request.append("\r\n");
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * URL 里的账号密码；未携带时返回 {@code null}，用于区分“没填凭据”与“凭据填错”。
     */
    private record Credentials(String user, String password) {

        static Credentials of(URI uri) {
            String userInfo = uri.getUserInfo();
            if (userInfo == null || userInfo.isBlank()) {
                return null;
            }
            int colon = userInfo.indexOf(':');
            return colon < 0
                ? new Credentials(userInfo, "")
                : new Credentials(userInfo.substring(0, colon), userInfo.substring(colon + 1));
        }
    }
}
