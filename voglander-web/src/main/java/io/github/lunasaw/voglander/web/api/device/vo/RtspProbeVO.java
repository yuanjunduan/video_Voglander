package io.github.lunasaw.voglander.web.api.device.vo;

import java.io.Serializable;

import io.github.lunasaw.voglander.intergration.wrapper.rtsp.dto.RtspProbeResult;
import lombok.Data;

/**
 * RTSP 地址连通性探测结果 VO。
 *
 * <p>
 * 与 {@link RtspProbeResult} 字段一一对应，仅做 Web 出口隔离：
 * Integration 的 DTO 不直接暴露给前端，后续改探测实现不影响接口契约。
 * </p>
 *
 * @author luna
 * @date 2026/10/26
 */
@Data
public class RtspProbeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否拿到 RTSP 协议响应（true 不等于"能播"）
     */
    private boolean reachable;

    /**
     * RTSP 状态码（200/401/404…）；未拿到响应时为 null
     */
    private Integer statusCode;

    /**
     * 状态行 reason phrase
     */
    private String  reason;

    /**
     * 是否要求鉴权（首次响应即 401/403）
     */
    private boolean authRequired;

    /**
     * 凭据是否被设备接受：地址带账号密码且 Digest 挑战通过才为 true；
     * 地址未带凭据时为 false（表示“未校验”，不等于密码错）
     */
    private boolean authenticated;

    /**
     * 探测目标 host:port（不含账号密码）
     */
    private String  target;

    /**
     * 耗时（毫秒）
     */
    private long    elapsedMs;

    /**
     * 结论描述，可直接展示给用户
     */
    private String  message;

    public static RtspProbeVO convertVO(RtspProbeResult result) {
        if (result == null) {
            return null;
        }
        RtspProbeVO vo = new RtspProbeVO();
        vo.setReachable(result.isReachable());
        vo.setStatusCode(result.getStatusCode());
        vo.setReason(result.getReason());
        vo.setAuthRequired(result.isAuthRequired());
        vo.setAuthenticated(result.isAuthenticated());
        vo.setTarget(result.getTarget());
        vo.setElapsedMs(result.getElapsedMs());
        vo.setMessage(result.getMessage());
        return vo;
    }
}
