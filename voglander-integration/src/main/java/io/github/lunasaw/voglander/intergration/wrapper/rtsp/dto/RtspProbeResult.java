package io.github.lunasaw.voglander.intergration.wrapper.rtsp.dto;

import java.io.IOException;
import java.io.Serializable;

import lombok.Data;

/**
 * RTSP 地址连通性探测结果。
 *
 * <p>
 * 语义分层（避免"能连通"和"能拉到流"混为一谈）：
 * <ul>
 *   <li>{@code reachable=true} 表示**拿到了 RTSP 协议响应**（TCP 通且对端是 RTSP 服务）；</li>
 *   <li>{@code statusCode=2xx} 才代表该 URL 本身可用；401/403 表示地址对但缺凭据，404 表示地址错；</li>
 *   <li>{@code authenticated} 表示**凭据真的被设备接受过**：仅当 URL 带账号密码、且带 Digest 重发后
 *       拿到 2xx 才为 true。它回答的是"密码对不对"，{@code reachable} 回答的是"设备在不在"；</li>
 *   <li>{@code reachable=false} 表示连不上、超时，或端口能连但返回的不是 RTSP 响应。</li>
 * </ul>
 * 本探测**不发 DESCRIBE**：各厂商对 DESCRIBE 的鉴权/通道号要求差异太大，深探会制造大量假失败。
 * 凭据校验用"OPTIONS 401 → 带 Digest 重发 OPTIONS"完成，因此必须在**同一条 TCP 连接**内进行
 * （部分设备把 nonce 绑在连接上，换连接重放必然 401）。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
@Data
public class RtspProbeResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否拿到 RTSP 协议响应（TCP 可达 + 状态行合法）
     */
    private boolean reachable;

    /**
     * RTSP 状态码（200/401/404…）；未拿到响应时为 null
     */
    private Integer statusCode;

    /**
     * 状态行 reason phrase（如 OK / Unauthorized）
     */
    private String  reason;

    /**
     * 是否要求鉴权（首次响应即 401/403，或带 Digest 重发前被挑战）
     */
    private boolean authRequired;

    /**
     * 凭据是否被设备接受：仅当 URL 带账号密码并在同一连接内通过 Digest 挑战后拿到 2xx 才为 true。
     * 地址不带凭据时为 false（未校验，不等于密码错）。
     */
    private boolean authenticated;

    /**
     * 探测目标 host:port（不含 URL 中的账号密码）
     */
    private String  target;

    /**
     * 耗时（毫秒，含建连 + 一次 OPTIONS 往返）
     */
    private long    elapsedMs;

    /**
     * 结论描述，可直接给前端提示
     */
    private String  message;

    /**
     * 不可达结果（网络层失败：拒绝连接、超时、DNS 失败等）。
     */
    public static RtspProbeResult unreachable(String target, long elapsedMs, IOException cause) {
        RtspProbeResult result = new RtspProbeResult();
        result.setReachable(false);
        result.setTarget(target);
        result.setElapsedMs(elapsedMs);
        result.setMessage("连接失败：" + describe(cause) + "（" + target + "）");
        return result;
    }

    /**
     * 不可达结果（TCP 通，但对端响应不是 RTSP，通常是端口填错）。
     */
    public static RtspProbeResult notRtsp(String target, long elapsedMs, String firstLine) {
        RtspProbeResult result = new RtspProbeResult();
        result.setReachable(false);
        result.setTarget(target);
        result.setElapsedMs(elapsedMs);
        // 首行为空（对端直接断开）与“返回了非 RTSP 内容”是两种情况，不能拼成一句自相矛盾的话
        result.setMessage(firstLine == null || firstLine.isBlank()
            ? "端口可连接，但未返回 RTSP 响应 —— 多半不是 RTSP 端口（通常 554）"
            : "端口可连接，但返回的不是 RTSP 响应（" + preview(firstLine) + "）—— 确认端口是否填错（RTSP 通常 554）");
        return result;
    }

    /**
     * 可达结果（已解析出 RTSP 状态码）。
     */
    public static RtspProbeResult reachable(String target, long elapsedMs, int statusCode, String reason) {
        RtspProbeResult result = new RtspProbeResult();
        result.setReachable(true);
        result.setTarget(target);
        result.setElapsedMs(elapsedMs);
        result.setStatusCode(statusCode);
        result.setReason(reason);
        result.setAuthRequired(statusCode == 401 || statusCode == 403);
        result.setMessage(buildMessage(statusCode, reason));
        return result;
    }

    /**
     * 设备要求鉴权，但 URL 里没写账号密码（无从校验，不能说成密码错）。
     */
    public static RtspProbeResult challengeWithoutCredential(String target, long elapsedMs, int statusCode,
                                                             String reason) {
        RtspProbeResult result = reachable(target, elapsedMs, statusCode, reason);
        result.setAuthRequired(true);
        result.setAuthenticated(false);
        result.setMessage("设备可达，但要求鉴权（" + status(statusCode, reason)
            + "）—— 地址里未带账号密码，请补成 rtsp://账号:密码@主机:端口/...");
        return result;
    }

    /**
     * 设备要求的认证方案探活暂不支持（非 Digest 或摘要算法为 SHA-256 等）。
     */
    public static RtspProbeResult challengeUnsupported(String target, long elapsedMs, int statusCode, String reason,
                                                       String scheme) {
        RtspProbeResult result = reachable(target, elapsedMs, statusCode, reason);
        result.setAuthRequired(true);
        result.setAuthenticated(false);
        if (scheme == null || scheme.isBlank()) {
            // 401 却没带 WWW-Authenticate：部分设备只在 DESCRIBE 才发挑战，OPTIONS 阶段无从校验
            result.setMessage("设备可达，但要求鉴权（" + status(statusCode, reason)
                + "）—— 设备未返回 WWW-Authenticate 挑战头，无法校验账号密码");
            return result;
        }
        result.setMessage("设备可达，但要求鉴权（" + status(statusCode, reason) + "）—— 设备使用 " + scheme
            + " 认证，探活目前只支持 Digest，无法校验账号密码");
        return result;
    }

    /**
     * 带凭据重发后被设备接受（Digest 挑战通过）。
     */
    public static RtspProbeResult credentialAccepted(String target, long elapsedMs, int statusCode, String reason) {
        RtspProbeResult result = reachable(target, elapsedMs, statusCode, reason);
        result.setAuthRequired(true);
        result.setAuthenticated(true);
        result.setMessage("设备可达，账号密码校验通过（" + status(statusCode, reason) + "）—— 仅完成协议握手，未校验收流");
        return result;
    }

    /**
     * 带凭据重发后仍被拒绝（密码错，或账号无该通道权限）。
     */
    public static RtspProbeResult credentialRejected(String target, long elapsedMs, int statusCode, String reason) {
        RtspProbeResult result = reachable(target, elapsedMs, statusCode, reason);
        result.setAuthRequired(true);
        result.setAuthenticated(false);
        result.setMessage("设备可达，但账号密码未通过校验（" + status(statusCode, reason) + "）—— 确认账号密码与通道权限");
        return result;
    }

    private static String buildMessage(int statusCode, String reason) {
        String statusText = status(statusCode, reason);
        if (statusCode >= 200 && statusCode < 300) {
            // 不能说“地址可用”：OPTIONS 只看服务端在不在（ZLM 对任意路径都回 200），不看流是否存在
            return "RTSP 服务可达（" + statusText + "）—— 仅完成协议握手，未校验收流";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "设备可达，但要求鉴权（" + statusText + "）—— 地址未带账号密码，或凭据未被接受";
        }
        if (statusCode == 404 || statusCode == 454) {
            return "设备可达，但该流地址不存在（" + statusText + "）—— 检查通道号 / 路径部分";
        }
        return "设备可达，返回 " + statusText;
    }

    private static String status(int statusCode, String reason) {
        return statusCode + (reason == null || reason.isBlank() ? "" : " " + reason);
    }

    /**
     * 把 IOException 翻译成可读原因，避免前端只看到 "Connection refused"。
     */
    private static String describe(IOException cause) {
        if (cause instanceof java.net.SocketTimeoutException) {
            return "连接或响应超时";
        }
        if (cause instanceof java.net.UnknownHostException) {
            return "域名/IP 无法解析";
        }
        if (cause instanceof java.net.ConnectException) {
            return "端口未开放或被拒绝";
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String preview(String firstLine) {
        String trimmed = firstLine == null ? "" : firstLine.trim();
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
    }
}
