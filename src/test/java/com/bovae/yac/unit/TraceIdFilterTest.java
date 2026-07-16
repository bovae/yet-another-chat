package com.bovae.yac.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.filter.TraceIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private static final String TRACE_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final TraceIdFilter filter = new TraceIdFilter();

    private String traceIdInsideChain;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void doFilter_shouldReuseInboundTraceId_whenHeaderPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(traceIdInsideChain).isEqualTo("trace-123");
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo("trace-123");
        assertThat(MDC.get(TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void doFilter_shouldGenerateTraceId_whenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(traceIdInsideChain).isNotBlank();
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(traceIdInsideChain);
        assertThat(MDC.get(TRACE_ID_MDC_KEY)).isNull();
    }

    @ParameterizedTest(name = "blank header=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    void doFilter_shouldGenerateTraceId_whenHeaderBlank(String blank) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, blank);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(traceIdInsideChain).isNotBlank();
        assertThat(traceIdInsideChain).isNotEqualTo(blank);
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(traceIdInsideChain);
    }

    private FilterChain capturingChain() {
        return (req, res) -> traceIdInsideChain = MDC.get(TRACE_ID_MDC_KEY);
    }
}
