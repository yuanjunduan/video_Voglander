package io.github.lunasaw.voglander.intergration.wrapper.rtsp.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RTSP Digest 认证挑战（RFC 2069 / RFC 2617）。
 *
 * <p>
 * 只实现探活所需的最小闭环：解析 {@code WWW-Authenticate: Digest realm/nonce/qop/opaque}，
 * 按给定方法生成 {@code Authorization} 头。不依赖 HTTP 客户端库——探活走裸 Socket，
 * 引入 HttpClient 反而要为 RTSP/1.0 状态行另做适配。
 * </p>
 *
 * <p>
 * 兼容三种摘要：无 {@code qop}（RFC 2069，大华等设备走这条）、{@code qop=auth}、
 * {@code qop=auth-int}；{@code algorithm=MD5-sess} 亦支持。{@code SHA-256} 等新算法
 * 不在覆盖范围内，返回 {@code null} 由调用方降级为"仅提示要求鉴权"。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
public final class DigestChallenge {

    /**
     * 认证参数：name="quoted" 或 name=token
     */
    private static final Pattern PARAM_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+)=(?:\"([^\"]*)\"|([^,\\s]*))");

    private static final String  SCHEME_DIGEST = "digest";

    /**
     * 单次挑战只发一条请求，nonce 计数固定
     */
    private static final String  NONCE_COUNT   = "00000001";

    private final Map<String, String> params;

    private DigestChallenge(Map<String, String> params) {
        this.params = params;
    }

    /**
     * 解析 {@code WWW-Authenticate} 头；非 Digest 或缺少 realm/nonce 时返回 {@code null}。
     */
    public static DigestChallenge parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        int space = trimmed.indexOf(' ');
        String scheme = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        if (!SCHEME_DIGEST.equals(scheme)) {
            // Basic 等其它方案：RTSP 设备极少使用，交由调用方降级提示
            return null;
        }
        Map<String, String> params = new HashMap<>();
        Matcher matcher = PARAM_PATTERN.matcher(space < 0 ? "" : trimmed.substring(space + 1));
        while (matcher.find()) {
            String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
            params.put(matcher.group(1).toLowerCase(Locale.ROOT), value == null ? "" : value);
        }
        if (params.get("realm") == null || params.get("nonce") == null) {
            return null;
        }
        return new DigestChallenge(params);
    }

    /**
     * 生成 {@code Authorization} 头的值（不含 "Authorization: " 前缀）。
     *
     * @param method   RTSP 方法（本探活只发 OPTIONS）
     * @param uri      与实际请求行**完全一致**的 URI（含 path + query，不带 userinfo）
     * @param user     账号
     * @param password 密码
     * @return 头值；算法不受支持时返回 {@code null}
     */
    public String authorization(String method, String uri, String user, String password) {
        String algorithm = params.getOrDefault("algorithm", "MD5");
        if (!algorithm.toLowerCase(Locale.ROOT).startsWith("md5")) {
            return null;
        }
        String realm = params.get("realm");
        String nonce = params.get("nonce");
        String cnonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        String ha1 = md5Hex(user + ":" + realm + ":" + password);
        if ("md5-sess".equalsIgnoreCase(algorithm)) {
            ha1 = md5Hex(ha1 + ":" + nonce + ":" + cnonce);
        }
        String qop = selectQop(params.get("qop"));
        // auth-int 的 HA2 要带实体摘要；OPTIONS 无 body，故摘要为空串的 MD5
        String ha2 = "auth-int".equals(qop)
            ? md5Hex(method + ":" + uri + ":" + md5Hex(""))
            : md5Hex(method + ":" + uri);

        StringBuilder header = new StringBuilder("Digest ")
            .append("username=\"").append(user).append("\", ")
            .append("realm=\"").append(realm).append("\", ")
            .append("nonce=\"").append(nonce).append("\", ")
            .append("uri=\"").append(uri).append("\", ");
        if (qop == null) {
            header.append("response=\"").append(md5Hex(ha1 + ":" + nonce + ":" + ha2)).append('"');
        } else {
            String digest = md5Hex(ha1 + ":" + nonce + ":" + NONCE_COUNT + ":" + cnonce + ":" + qop + ":" + ha2);
            header.append("response=\"").append(digest).append("\", ")
                .append("qop=").append(qop).append(", ")
                .append("nc=").append(NONCE_COUNT).append(", ")
                .append("cnonce=\"").append(cnonce).append('"');
        }
        if (params.get("opaque") != null) {
            header.append(", opaque=\"").append(params.get("opaque")).append('"');
        }
        return header.toString();
    }

    /**
     * 从 {@code qop="auth,auth-int"} 里挑一个；都不可用时返回 null（退化为 RFC 2069 计算）。
     */
    private static String selectQop(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String token : raw.split(",")) {
            if ("auth".equalsIgnoreCase(token.trim())) {
                return "auth";
            }
        }
        for (String token : raw.split(",")) {
            if ("auth-int".equalsIgnoreCase(token.trim())) {
                return "auth-int";
            }
        }
        return null;
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5 摘要", e);
        }
    }
}
