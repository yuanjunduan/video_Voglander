package io.github.lunasaw.voglander.intergration.wrapper.rtsp.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条 RTSP 响应（只解析状态行与响应头）。
 *
 * <p>
 * 探活需要在**同一条 TCP 连接**上连读两条响应（匿名 OPTIONS 拿到 401 挑战，再带
 * Digest 重发），所以不能沿用"读一行状态行就收工"的写法：每次读取必须把响应头吃干净，
 * 否则上一条的残留头会被下一条误读成状态行。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
public final class RtspResponse {

    /**
     * 状态行：RTSP/1.0 200 OK
     */
    private static final Pattern STATUS_LINE = Pattern.compile("^RTSP/\\d\\.\\d\\s+(\\d{3})\\s*(.*)$");

    /**
     * 最多跳过几行前置空行
     */
    private static final int MAX_SCAN_LINES = 3;

    private final String statusLine;

    private final boolean rtsp;

    private final int statusCode;

    private final String reason;

    /**
     * 响应头（key 统一小写，保留全部同名值以应对多段 WWW-Authenticate 挑战）
     */
    private final Map<String, List<String>> headers = new HashMap<>();

    private RtspResponse(String statusLine) {
        this.statusLine = statusLine;
        Matcher matcher = statusLine == null ? null : STATUS_LINE.matcher(statusLine.trim());
        this.rtsp = matcher != null && matcher.matches();
        this.statusCode = rtsp ? Integer.parseInt(matcher.group(1)) : 0;
        this.reason = rtsp ? matcher.group(2) : null;
    }

    /**
     * 读取一条响应；对端直接关闭（EOF）时返回 {@code null}。
     *
     * <p>
     * 非 RTSP 响应（如 HTTP）不再继续读头：调用方会立即判"端口填错"并断开，读下去只会等超时。
     * </p>
     */
    public static RtspResponse read(BufferedReader reader) throws IOException {
        String line = readStatusLine(reader);
        if (line == null) {
            return null;
        }
        RtspResponse response = new RtspResponse(line);
        if (response.rtsp) {
            response.readHeaders(reader);
        }
        return response;
    }

    private static String readStatusLine(BufferedReader reader) throws IOException {
        for (int i = 0; i < MAX_SCAN_LINES; i++) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    private void readHeaders(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                return;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
    }

    public String getStatusLine() {
        return statusLine;
    }

    public boolean isRtsp() {
        return rtsp;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 取首个同名响应头（大小写不敏感），不存在返回 {@code null}。
     */
    public String firstHeader(String name) {
        List<String> values = headers.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * 2xx
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * 401/403：要求鉴权
     */
    public boolean isAuthChallenge() {
        return statusCode == 401 || statusCode == 403;
    }
}
