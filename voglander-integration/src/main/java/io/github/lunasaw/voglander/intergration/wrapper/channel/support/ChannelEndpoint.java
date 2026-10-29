package io.github.lunasaw.voglander.intergration.wrapper.channel.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import io.github.lunasaw.voglander.manager.domaon.dto.DeviceDTO;

/**
 * 从设备配置里解析出的"发现端点"：管理面的地址与凭据。
 *
 * <p>
 * 平台里拉流设备只填了 RTSP 地址（{@code rtsp://user:password@host:554/...}），而查通道台账走的是
 * 设备的管理面 HTTP（海康 ISAPI 在 80、大华 CGI 在 80）。两者共用同一个 host 与同一组凭据，
 * 所以这里统一从 RTSP 地址里解析，避免再引入第二套地址/账号字段。
 * </p>
 *
 * @author luna
 * @date 2026/10/27
 */
public class ChannelEndpoint {

    /** 设备管理面 HTTP 默认端口 */
    public static final int DEFAULT_HTTP_PORT = 80;

    /** RTSP 默认端口 */
    public static final int DEFAULT_RTSP_PORT = 554;

    private final String    host;

    private final int       httpPort;

    private final int       rtspPort;

    private final String    user;

    private final String    password;

    private ChannelEndpoint(String host, int httpPort, int rtspPort, String user, String password) {
        this.host = host;
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
        this.user = user;
        this.password = password;
    }

    /**
     * 从设备解析端点：优先用 {@code extendInfo.rtspUrl}（含 host/端口/凭据），
     * 该字段缺失时回落 {@code ip} + 默认端口（此时大概率没有凭据，发现会因 401 失败并如实报错）。
     *
     * @return 解析结果；连 host 都没有时返回 {@code null}
     */
    public static ChannelEndpoint of(DeviceDTO device) {
        if (device == null) {
            return null;
        }
        String rtspUrl = device.getExtendInfo() == null ? null : device.getExtendInfo().getRtspUrl();
        if (rtspUrl != null && !rtspUrl.isBlank()) {
            ChannelEndpoint parsed = parseRtspUrl(rtspUrl.trim());
            if (parsed != null) {
                return parsed;
            }
        }
        String ip = device.getIp();
        if (ip == null || ip.isBlank()) {
            return null;
        }
        int rtspPort = device.getPort() == null || device.getPort() <= 0 ? DEFAULT_RTSP_PORT : device.getPort();
        return new ChannelEndpoint(ip.trim(), DEFAULT_HTTP_PORT, rtspPort, null, null);
    }

    private static ChannelEndpoint parseRtspUrl(String rtspUrl) {
        try {
            URI uri = new URI(rtspUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int rtspPort = uri.getPort() > 0 ? uri.getPort() : DEFAULT_RTSP_PORT;
            String user = null;
            String password = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                int colon = userInfo.indexOf(':');
                if (colon < 0) {
                    user = decode(userInfo);
                    password = "";
                } else {
                    user = decode(userInfo.substring(0, colon));
                    password = decode(userInfo.substring(colon + 1));
                }
            }
            return new ChannelEndpoint(host, DEFAULT_HTTP_PORT, rtspPort, user, password);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 是否有可用主机
     */
    public boolean hasHost() {
        return host != null && !host.isBlank();
    }

    /**
     * 是否有账号（没有账号时发现必然 401，提前给出可理解的失败原因）
     */
    public boolean hasCredential() {
        return user != null && !user.isBlank();
    }

    /**
     * 拼管理面 GET 地址
     */
    public String httpUrl(String path) {
        return "http://" + host + ":" + httpPort + path;
    }

    /**
     * 拼通道 RTSP 地址（含凭据；无凭据时返回不带 userinfo 的地址）
     */
    public String rtspUrl(String pathAndQuery) {
        String userInfo = hasCredential() ? user + ":" + (password == null ? "" : password) + "@" : "";
        return "rtsp://" + userInfo + host + ":" + rtspPort + pathAndQuery;
    }

    public String getHost() {
        return host;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getRtspPort() {
        return rtspPort;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }
}
