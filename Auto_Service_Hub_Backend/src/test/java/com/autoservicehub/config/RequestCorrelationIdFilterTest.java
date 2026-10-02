package com.autoservicehub.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RequestCorrelationIdFilter} (SRS 15, 19).
 *
 * <p>Pure unit tests with no Spring context: the filter is exercised directly
 * with mock request/response objects, which keeps the assertion on the three
 * things that actually matter — that an id is always present, that a caller or
 * proxy can supply its own, and that the MDC never leaks between pooled threads.
 */
class RequestCorrelationIdFilterTest {

    private RequestCorrelationIdFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RequestCorrelationIdFilter();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("C1 an id is generated when the caller supplies none")
    void generatesIdWhenAbsent() throws Exception {
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = MDC.get(RequestCorrelationIdFilter.MDC_KEY);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/customers"), response, chain);

        assertThat(response.getHeader(RequestCorrelationIdFilter.HEADER)).isNotBlank();
        assertThat(seen[0]).isEqualTo(response.getHeader(RequestCorrelationIdFilter.HEADER));
    }

    @Test
    @DisplayName("C2 a caller-supplied id is honoured and echoed back")
    void honoursInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.addHeader(RequestCorrelationIdFilter.HEADER, "trace-from-gateway-123");

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestCorrelationIdFilter.HEADER)).isEqualTo("trace-from-gateway-123");
    }

    @Test
    @DisplayName("C3 the id is also set as a request attribute for the exception handler")
    void setsRequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.addHeader(RequestCorrelationIdFilter.HEADER, "abc-123");

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(request.getAttribute(RequestCorrelationIdFilter.ATTRIBUTE)).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("C4 the MDC is cleared after the request, so nothing leaks onto the next one")
    void clearsMdcAfterHandling() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/customers"), response, (req, res) -> { });

        // A pooled request thread would otherwise tag an unrelated later request
        // with this request's id.
        assertThat(MDC.get(RequestCorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("C5 a hostile inbound id is discarded rather than echoed")
    void rejectsUnsafeInboundId() throws Exception {
        // The value is echoed into a response header and a log file, so CR/LF must
        // never be able to ride through it.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.addHeader(RequestCorrelationIdFilter.HEADER, "bad\r\nX-Injected: yes");

        filter.doFilter(request, response, (req, res) -> { });

        String echoed = response.getHeader(RequestCorrelationIdFilter.HEADER);
        assertThat(echoed).isNotNull().doesNotContain("\n").doesNotContain("\r")
                .doesNotContain("X-Injected");
    }

    @Test
    @DisplayName("C6 an over-long inbound id is discarded")
    void rejectsOverLongInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customers");
        request.addHeader(RequestCorrelationIdFilter.HEADER, "a".repeat(500));

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestCorrelationIdFilter.HEADER)).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("C7 the MDC is cleared even when the request throws")
    void clearsMdcAfterException() {
        assertThat(catchRuntime(() -> filter.doFilter(new MockHttpServletRequest(), response,
                (req, res) -> { throw new IllegalStateException("boom"); })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(RequestCorrelationIdFilter.MDC_KEY)).isNull();
    }

    private static RuntimeException catchRuntime(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException ex) {
            return ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}