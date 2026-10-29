package io.github.lunasaw.voglander.intergration.wrapper.rtsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.lunasaw.voglander.intergration.wrapper.rtsp.dto.RtspProbeResult;

/**
 * {@link RtspProbeClient} 单元测试。
 *
 * <p>
 * 不依赖真实设备：用本地 {@link ServerSocket} 假冒 RTSP 服务，覆盖
 * 200 / 401 / 454 / 非 RTSP 响应 / 拒绝连接 / 读超时 / 非法入参 / 凭据不外泄，
 * 以及 Digest 挑战的三种走向（密码对、密码错、设备只支持 Basic）。
 * 挑战与重发必须在同一连接内完成（大华等设备把 nonce 绑在 TCP 连接上），
 * 所以假服务只 accept 一次、循环读多条请求。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
@DisplayName("RTSP 地址探活 — OPTIONS 判定")
class RtspProbeClientTest {

    private static final int FAST_TIMEOUT_MS = 500;

    private final RtspProbeClient client = new RtspProbeClient();

    @Test
    @DisplayName("200 OK → 可达，但文案只声明“服务可达”，不承诺流存在")
    void probe_200_shouldBeReachable() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n")) {
            RtspProbeResult result = client.probe(url(server.port(), "/live/rtsp_src"), FAST_TIMEOUT_MS);

            assertTrue(result.isReachable());
            assertEquals(200, result.getStatusCode());
            assertFalse(result.isAuthRequired());
            assertEquals("127.0.0.1:" + server.port(), result.getTarget());
            assertTrue(result.getMessage().contains("服务可达"), result.getMessage());
            assertTrue(result.getMessage().contains("未校验收流"), result.getMessage());
        }
    }

    @Test
    @DisplayName("对端直接断开（无任何响应）→ 判非 RTSP，且文案不自相矛盾")
    void probe_silentClose_shouldNotClaimGotResponse() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning(null)) {
            RtspProbeResult result = client.probe(url(server.port(), "/live/x"), FAST_TIMEOUT_MS);

            assertFalse(result.isReachable());
            assertTrue(result.getMessage().contains("未返回 RTSP 响应"), result.getMessage());
            assertFalse(result.getMessage().contains("返回的不是"), result.getMessage());
        }
    }

    @Test
    @DisplayName("401 且地址无凭据 → 可达但要求鉴权，提示补全账号密码（发起一条请求即止）")
    void probe_401_shouldReportAuthRequired() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
            + "WWW-Authenticate: Digest realm=\"Login to dev\", nonce=\"n1\"\r\n\r\n")) {
            RtspProbeResult result = client.probe(url(server.port(), "/live/rtsp_src"), FAST_TIMEOUT_MS);

            assertTrue(result.isReachable());
            assertEquals(401, result.getStatusCode());
            assertTrue(result.isAuthRequired());
            assertFalse(result.isAuthenticated(), "未携带凭据时不能声称校验通过");
            assertTrue(result.getMessage().contains("未带账号密码"), result.getMessage());
            assertEquals(1, server.seenRequests().size(), "无凭据时不应发起重发");
        }
    }

    @Test
    @DisplayName("带凭据 + Digest 挑战 + 密码正确 → authenticated=true，两条请求同连接、不含明文密码")
    void probe_digestCorrectCredential_shouldBeAuthenticated() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.scripted((port, request) -> {
            if (!request.contains("Authorization:")) {
                return "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
                    + "WWW-Authenticate: Digest realm=\"realms/dahua\", nonce=\"n-abc\"\r\n\r\n";
            }
            // 用同一 realm/nonce 复算，能命中才回 200：验证 uri、方法、HA1 拼法都对
            String expected = expectedDigest(port, "realms/dahua", "n-abc", "admin", "Admin123",
                "/cam/realmonitor?channel=1&subtype=1");
            return request.contains(expected)
                ? "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"
                : "RTSP/1.0 401 Unauthorized\r\nCSeq: 2\r\n\r\n";
        }, 2)) {
            RtspProbeResult result = client.probe("rtsp://admin:Admin123@127.0.0.1:" + server.port()
                + "/cam/realmonitor?channel=1&subtype=1", FAST_TIMEOUT_MS);

            assertTrue(result.isReachable());
            assertTrue(result.isAuthenticated(), result.getMessage());
            assertEquals(200, result.getStatusCode());
            assertTrue(result.getMessage().contains("账号密码校验通过"), result.getMessage());

            List<String> seen = server.seenRequests();
            assertEquals(2, seen.size(), "挑战 + 重发应在同一连接内各一条");
            assertFalse(seen.get(1).contains("Admin123"), "Authorization 头不得含明文密码：" + seen.get(1));
            assertTrue(seen.get(1).contains("username=\"admin\""), seen.get(1));
            assertTrue(seen.get(1).contains("nonce=\"n-abc\""), seen.get(1));
        }
    }

    @Test
    @DisplayName("带凭据 + Digest 挑战 + 密码错误 → authenticated=false，指向账号密码而非不可达")
    void probe_digestWrongCredential_shouldBeRejected() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.scripted((port, request) -> request.contains("Authorization:")
            ? "RTSP/1.0 401 Unauthorized\r\nCSeq: 2\r\n\r\n"
            : "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
                + "WWW-Authenticate: Digest realm=\"realms/dahua\", nonce=\"n-abc\"\r\n\r\n", 2)) {
            RtspProbeResult result = client.probe("rtsp://admin:wrong@127.0.0.1:" + server.port()
                + "/cam/realmonitor?channel=1", FAST_TIMEOUT_MS);

            assertTrue(result.isReachable(), "设备在，只是密码不对");
            assertFalse(result.isAuthenticated());
            assertEquals(401, result.getStatusCode());
            assertTrue(result.getMessage().contains("未通过校验"), result.getMessage());
        }
    }

    @Test
    @DisplayName("设备只支持 Basic → 降级为“无法校验”，不误报密码错")
    void probe_basicChallenge_shouldReportUnsupported() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
            + "WWW-Authenticate: Basic realm=\"ipcam\"\r\n\r\n")) {
            RtspProbeResult result = client.probe("rtsp://admin:Admin123@127.0.0.1:" + server.port()
                + "/Streaming/Channels/101", FAST_TIMEOUT_MS);

            assertTrue(result.isReachable());
            assertFalse(result.isAuthenticated());
            assertTrue(result.getMessage().contains("只支持 Digest"), result.getMessage());
            assertTrue(result.getMessage().contains("Basic"), result.getMessage());
            assertEquals(1, server.seenRequests().size(), "不支持的方案不应发起重发");
        }
    }

    @Test
    @DisplayName("454 Session Not Found → 可达但流地址不存在")
    void probe_454_shouldReportMissingStream() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("RTSP/1.0 454 Session Not Found\r\n\r\n")) {
            RtspProbeResult result = client.probe(url(server.port(), "/wrong/path"), FAST_TIMEOUT_MS);

            assertTrue(result.isReachable());
            assertEquals(454, result.getStatusCode());
            assertFalse(result.isAuthRequired());
            assertTrue(result.getMessage().contains("不存在"), result.getMessage());
        }
    }

    @Test
    @DisplayName("端口通但回 HTTP → 判非 RTSP（多半端口填错）")
    void probe_httpResponse_shouldNotBeRtsp() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("HTTP/1.1 400 Bad Request\r\n\r\n")) {
            RtspProbeResult result = client.probe(url(server.port(), "/live/rtsp_src"), FAST_TIMEOUT_MS);

            assertFalse(result.isReachable());
            assertNull(result.getStatusCode());
            assertTrue(result.getMessage().contains("不是 RTSP"), result.getMessage());
        }
    }

    @Test
    @DisplayName("端口未开放 → 不可达且文案给出原因")
    void probe_closedPort_shouldBeUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        RtspProbeResult result = client.probe(url(closedPort, "/live/rtsp_src"), FAST_TIMEOUT_MS);

        assertFalse(result.isReachable());
        assertTrue(result.getMessage().contains("连接失败"), result.getMessage());
    }

    @Test
    @DisplayName("建连成功但对端不回包 → 超时且不长时间阻塞")
    void probe_silentServer_shouldTimeout() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.silent()) {
            long start = System.currentTimeMillis();
            RtspProbeResult result = client.probe(url(server.port(), "/live/rtsp_src"), 300);
            long cost = System.currentTimeMillis() - start;

            assertFalse(result.isReachable());
            assertNull(result.getStatusCode());
            assertTrue(result.getMessage().contains("超时"), result.getMessage());
            assertTrue(cost < 3000, "探活不应长时间阻塞调用线程，实际 " + cost + "ms");
        }
    }

    @Test
    @DisplayName("URL 带账号密码 → 请求行里不带 userinfo（避免凭据外泄）")
    void probe_withCredentials_shouldStripUserInfoFromRequestLine() throws Exception {
        try (FakeRtspServer server = FakeRtspServer.returning("RTSP/1.0 200 OK\r\n\r\n")) {
            client.probe("rtsp://admin:s3cret@127.0.0.1:" + server.port() + "/Streaming/Channels/101", FAST_TIMEOUT_MS);

            String seen = server.seenRequest();
            assertTrue(seen.startsWith("OPTIONS rtsp://127.0.0.1:"), seen);
            assertFalse(seen.contains("s3cret"), "请求行不得出现凭据：" + seen);
        }
    }

    @Test
    @DisplayName("非 rtsp:// 或空地址 → 抛 IllegalArgumentException（由 Web 层转 400 语义）")
    void probe_invalidUrl_shouldThrow() {
        IllegalArgumentException http = assertThrows(IllegalArgumentException.class,
            () -> client.probe("http://127.0.0.1:554/live/x"));
        assertTrue(http.getMessage().contains("只支持 rtsp"),
            http.getMessage());

        assertThrows(IllegalArgumentException.class, () -> client.probe("  "));
        assertThrows(IllegalArgumentException.class, () -> client.probe("rtsp:///live/x"));
    }

    private static String url(int port, String path) {
        return "rtsp://127.0.0.1:" + port + path;
    }

    /**
     * 假冒 RTSP 服务。
     *
     * <p>
     * 支持单轮应答与脚本化多轮：凭据校验必须“挑战 → 重发”两条请求走<b>同一个连接</b>，
     * 所以脚本化实现只 accept 一次、循环读多条请求。
     * </p>
     */
    private static final class FakeRtspServer implements AutoCloseable {

        private final ServerSocket socket;

        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

        private FakeRtspServer(BiFunction<Integer, String, String> responder, int rounds, boolean silent)
            throws IOException {
            this.socket = new ServerSocket(0);
            Thread thread = new Thread(() -> serve(responder, rounds, silent), "fake-rtsp-probe");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * 固定回应一次；响应为 {@code null} 表示立刻关闭连接。
         */
        static FakeRtspServer returning(String response) throws IOException {
            return new FakeRtspServer((port, request) -> response, 1, false);
        }

        /**
         * 按脚本回 {@code rounds} 轮；responder 拿到本端端口（用于复算 Digest 的 uri）与请求原文。
         */
        static FakeRtspServer scripted(BiFunction<Integer, String, String> responder, int rounds)
            throws IOException {
            return new FakeRtspServer(responder, rounds, false);
        }

        /**
         * 建连后不回任何包，用于读超时场景。
         */
        static FakeRtspServer silent() throws IOException {
            return new FakeRtspServer(null, 1, true);
        }

        private void serve(BiFunction<Integer, String, String> responder, int rounds, boolean silent) {
            try (Socket client = socket.accept()) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = client.getOutputStream();
                for (int i = 0; i < rounds; i++) {
                    String request = readRequest(reader);
                    if (request == null) {
                        return;
                    }
                    requests.add(request);
                    if (silent) {
                        Thread.sleep(1500);
                        return;
                    }
                    String response = responder.apply(socket.getLocalPort(), request);
                    if (response == null) {
                        return;
                    }
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                // 回完最后一轮别立即关闭：客户端可能还在读，提前 EOF 会掩盖真实断言
                Thread.sleep(50);
            } catch (Exception ignored) {
                // 假服务：异常不影响断言，超时/关闭场景本就会在此处断开
            }
        }

        private static String readRequest(BufferedReader reader) throws IOException {
            StringBuilder request = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                request.append(line).append("\r\n");
                if (line.isEmpty()) {
                    return request.toString();
                }
            }
            return request.isEmpty() ? null : request.toString();
        }

        int port() {
            return socket.getLocalPort();
        }

        /**
         * 收到的请求原文（按到达顺序）。
         */
        List<String> seenRequests() {
            return new ArrayList<>(requests);
        }

        String seenRequest() {
            return requests.isEmpty() ? "" : requests.get(0);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * 复算 RFC 2069（无 qop）摘要，与设备侧算法保持一致。
     */
    private static String expectedDigest(int port, String realm, String nonce, String user, String password,
                                         String pathAndQuery) {
        String requestUri = "rtsp://127.0.0.1:" + port + pathAndQuery;
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5("OPTIONS:" + requestUri);
        return md5(ha1 + ":" + nonce + ":" + ha2);
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
