package io.github.lunasaw.voglander.intergration.wrapper.channel.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import io.github.lunasaw.voglander.intergration.wrapper.rtsp.support.DigestChallenge;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 最小 HTTP 客户端：GET + Digest 认证（RFC 2617），只服务于通道发现这类"读取设备台账"的请求。
 *
 * <p>
 * 为什么不用 RestTemplate / HttpClient：本类只需要"GET 一次、401 就带 Digest 重发一次、返回原始文本"，
 * 不涉及连接池、拦截器、消息转换；JDK 自带 {@link HttpURLConnection} 足够，且避免为一个只读探测
 * 引入新依赖。重定向显式关闭——设备被墙或未授权时常 302 到登录页，跟随过去会拿到 200 的 HTML，
 * 用状态码判定就会误判成"成功"。
 * </p>
 *
 * <p>
 * Digest 摘要直接复用 RTSP 探活的 {@link DigestChallenge}：HTTP 与 RTSP 的 Digest 计算规则相同
 * （仅 method 与 request-uri 取值不同），没有第二份实现的必要。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
@Slf4j
@Component
public class HttpDigestClient {

    private static final String METHOD_GET         = "GET";

    private static final String HEADER_AUTHORIZATION  = "Authorization";

    private static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";

    private static final String USER_AGENT         = "VoglanderDiscover/1.0";

    /**
     * 发一个 GET；设备要求 Digest 时用给定凭据重发一次。
     *
     * @param url       完整地址（http://host:port/path?query）
     * @param user      账号，可空（空则不做认证重发）
     * @param password  密码
     * @param timeoutMs 建连与读超时
     * @return 响应；网络类异常不抛出，以 {@code statusCode=-1} + message 返回
     */
    public Response get(String url, String user, String password, int timeoutMs) {
        Response first = execute(url, null, timeoutMs);
        if (first.getStatusCode() != HttpURLConnection.HTTP_UNAUTHORIZED) {
            first.setAuthenticated(first.isSuccess());
            return first;
        }
        if (user == null || user.isBlank()) {
            first.setMessage("设备要求鉴权，但未解析到账号（拉流地址里需带 user:password）");
            return first;
        }

        String challengeHeader = first.getChallengeHeader();
        DigestChallenge challenge = DigestChallenge.parse(challengeHeader);
        if (challenge == null) {
            first.setMessage("设备要求的认证方式暂不支持：" + describeScheme(challengeHeader));
            return first;
        }
        String authorization = challenge.authorization(METHOD_GET, pathAndQuery(url), user, password);
        if (authorization == null) {
            first.setMessage("摘要算法暂不支持（仅支持 MD5 / MD5-sess）");
            return first;
        }

        Response second = execute(url, authorization, timeoutMs);
        second.setAuthenticated(second.isSuccess());
        if (!second.isSuccess()) {
            second.setMessage("账号或密码错误（HTTP " + second.getStatusCode() + "）");
        }
        return second;
    }

    /**
     * 发一次请求；{@code authorization} 为 null 时是匿名请求。
     */
    private Response execute(String url, String authorization, int timeoutMs) {
        Response response = new Response();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(METHOD_GET);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (authorization != null) {
                conn.setRequestProperty(HEADER_AUTHORIZATION, authorization);
            }

            int code = conn.getResponseCode();
            response.setStatusCode(code);
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                response.setChallengeHeader(conn.getHeaderField(HEADER_WWW_AUTHENTICATE));
                response.setMessage("设备要求 Digest 鉴权");
            }
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            response.setBody(readAll(stream));
            if (code < 200 || code >= 300) {
                if (response.getMessage() == null) {
                    response.setMessage("HTTP " + code);
                }
            }
            return response;
        } catch (IOException e) {
            response.setStatusCode(-1);
            response.setMessage("连接失败：" + e.getMessage());
            log.warn("[http-digest] 请求失败 url={} err={}", url, e.getMessage());
            return response;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Digest 的 uri 参数必须与实际请求行一致：path + query，不含 scheme/host。
     */
    private String pathAndQuery(String url) {
        try {
            URL u = new URL(url);
            String file = u.getFile();
            return (file == null || file.isEmpty()) ? "/" : file;
        } catch (IOException e) {
            // 上面 execute 已握过同一个 URL，走到这里说明入参本来就非法
            return "/";
        }
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * 取认证方案名，仅用于失败文案（Digest / Basic / …）。
     */
    private String describeScheme(String challengeHeader) {
        if (challengeHeader == null || challengeHeader.isBlank()) {
            return "未知";
        }
        String trimmed = challengeHeader.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /**
     * HTTP 响应（只保留发现场景需要的部分）。
     */
    @Data
    public static class Response {

        /**
         * HTTP 状态码；网络层失败为 -1
         */
        private int     statusCode;

        /**
         * 响应体（UTF-8 解码）
         */
        private String  body;

        /**
         * 失败/鉴权说明，成功时为 null
         */
        private String  message;

        /**
         * 是否已完成认证（2xx 且本次请求通过鉴权）
         */
        private boolean authenticated;

        /**
         * 401 时的 WWW-Authenticate 原始头
         */
        private String  challengeHeader;

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
