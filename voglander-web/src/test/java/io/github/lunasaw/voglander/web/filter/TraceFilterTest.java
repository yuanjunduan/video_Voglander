package io.github.lunasaw.voglander.web.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.github.lunasaw.sip.common.utils.TraceUtils;
import io.github.lunasaw.voglander.common.constant.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@link TraceFilter} 单元测试：请求头透传、MDC 写入与清理、非法 traceId 兜底。
 */
@DisplayName("TraceFilter 单元测试")
class TraceFilterTest {

    private static final String TRACE_ID_KEY = "traceId";

    private final TraceFilter filter = new TraceFilter();

    @AfterEach
    void clearTrace() {
        /* 失败用例也要把 ThreadLocal / MDC 清干净，避免污染同线程的其它测试 */
        TraceUtils.clearTraceId();
    }

    @Test
    @DisplayName("请求头带合法 traceId：写入 MDC、回显响应头，请求结束清理")
    void reuseIncomingTraceId() throws Exception {
        HttpServletRequest       request  = mockRequest("abc123def456abcd");
        HttpServletResponse      response = mock(HttpServletResponse.class);
        AtomicReference<String>  inChain  = new AtomicReference<>();

        filter.doFilter(request, response, chainCapturing(inChain));

        assertEquals("abc123def456abcd", inChain.get(), "链路执行期间 MDC 里应能看到前端传来的 traceId");
        verify(response).setHeader(Constants.X_TRACE_ID, "abc123def456abcd");
        assertNull(TraceUtils.getCurrentTraceId(), "请求结束后必须清理 traceId");
        assertNull(MDC.get(TRACE_ID_KEY), "MDC 残留会让后续请求串到别人的日志里");
    }

    @Test
    @DisplayName("缺少 X-Trace-Id：生成 16 位 traceId 并回显")
    void generateWhenHeaderMissing() throws Exception {
        HttpServletRequest      request  = mockRequest(null);
        HttpServletResponse     response = mock(HttpServletResponse.class);
        AtomicReference<String> inChain  = new AtomicReference<>();

        filter.doFilter(request, response, chainCapturing(inChain));

        assertNotNull(inChain.get());
        assertEquals(16, inChain.get().length());
        verify(response).setHeader(Constants.X_TRACE_ID, inChain.get());
    }

    @Test
    @DisplayName("非法 X-Trace-Id（含空格/换行/超长）：改用服务端生成的值")
    void rejectIllegalTraceId() throws Exception {
        String[] illegal = {"bad trace id", "abc\r\nInjected: x", "short", "x".repeat(65)};
        for (String value : illegal) {
            HttpServletRequest      request  = mockRequest(value);
            HttpServletResponse     response = mock(HttpServletResponse.class);
            AtomicReference<String> inChain  = new AtomicReference<>();

            filter.doFilter(request, response, chainCapturing(inChain));

            assertNotEquals(value, inChain.get(), "非法值不能进入 MDC: " + value);
            assertTrue(inChain.get().matches("[A-Za-z0-9_-]{8,64}"), "兜底值仍须合法: " + inChain.get());
            TraceUtils.clearTraceId();
        }
    }

    @Test
    @DisplayName("链路抛异常：异常继续向外抛，traceId 仍被清理")
    void clearTraceIdWhenChainThrows() {
        HttpServletRequest  request  = mockRequest("abc123def456abcd");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, resp) -> {
            throw new ServletException("boom");
        }));
        assertNull(TraceUtils.getCurrentTraceId());
    }

    // ============================ helpers ============================

    private HttpServletRequest mockRequest(String traceIdHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(Constants.X_TRACE_ID)).thenReturn(traceIdHeader);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/cascade/channel");
        return request;
    }

    /** 记录链路执行期间当前线程的 traceId */
    private FilterChain chainCapturing(AtomicReference<String> holder) {
        return (req, resp) -> holder.set(TraceUtils.getCurrentTraceId());
    }
}
