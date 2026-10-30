package io.github.lunasaw.voglander.web.filter;

import java.io.IOException;
import java.util.regex.Pattern;

import io.github.lunasaw.sip.common.utils.TraceUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lunasaw.voglander.common.constant.Constants;
import jakarta.servlet.*;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求级 traceId 过滤器：把「前端一次页面操作」和「后端全链路日志」按同一个 ID 串起来。
 *
 * <p>取值顺序：请求头 {@code X-Trace-Id}（前端请求拦截器每次请求生成）优先，缺失或非法时由
 * {@link TraceUtils#generateTraceId()} 兜底。traceId 同时写入：
 * <ul>
 *   <li>{@link TraceUtils} 的 ThreadLocal + MDC（key 为 {@code traceId}，日志 pattern 里
 *       {@code %X{traceId}} 即可输出），SIP 侧代码也用同一个 {@link TraceUtils} 取值；</li>
 *   <li>响应头 {@code X-Trace-Id}，前端报错时可直接把该 ID 抄给运维定位。</li>
 * </ul>
 *
 * <p>请求结束后必须在 {@code finally} 里清理：Tomcat 线程池会复用线程，残留的 MDC 会让下一条
 * 无关请求的日志带上别人的 traceId。
 *
 * @author luna
 * @date 2024/6/27
 */
public class TraceFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    /**
     * 合法的 traceId：只允许字母、数字、下划线、短横线，长度 8~64。
     *
     * <p>请求头是外部可控输入，若原样写入 MDC，则可能被塞入换行等字符伪造日志行（日志注入），
     * 故先做白名单校验，不合规一律改用服务端生成的 ID。
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        Filter.super.init(filterConfig);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest)servletRequest;
        HttpServletResponse resp = (HttpServletResponse)servletResponse;

        String traceId = resolveTraceId(req);
        TraceUtils.setTraceId(traceId);
        resp.setHeader(Constants.X_TRACE_ID, traceId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(req, resp);
        } finally {
            long cost = System.currentTimeMillis() - start;
            /* traceId 由日志 pattern 的 %X{traceId} 输出，此处不必重复拼接 */
            log.info("HTTP {} {} -> {} ({}ms)", req.getMethod(), req.getRequestURI(), resp.getStatus(), cost);
            TraceUtils.clearTraceId();
        }
    }

    @Override
    public void destroy() {
        Filter.super.destroy();
    }

    private String resolveTraceId(HttpServletRequest req) {
        String header = req.getHeader(Constants.X_TRACE_ID);
        if (StringUtils.isBlank(header)) {
            return TraceUtils.generateTraceId();
        }
        String candidate = header.trim();
        if (!SAFE_TRACE_ID.matcher(candidate).matches()) {
            /* 不回显原始值：非法内容本身可能就是想写进日志的载荷 */
            log.debug("X-Trace-Id 头不合法（长度或字符集），改用服务端生成的 traceId");
            return TraceUtils.generateTraceId();
        }
        return candidate;
    }
}
