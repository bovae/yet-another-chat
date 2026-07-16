package com.bovae.yac.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Tags every request with a trace id, exposing it to the SLF4J {@link MDC} and the
 * {@code X-Request-Id} response header so all log lines for one request can be correlated.
 *
 * <p>An inbound {@code X-Request-Id} header is reused when present (e.g. propagated by an upstream
 * proxy or gateway); otherwise a random id is generated. Ordered first so authentication and error
 * handling already run inside the trace context.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Request-Id";
    static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String inbound = request.getHeader(TRACE_ID_HEADER);
        if (inbound != null && !inbound.isBlank()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
