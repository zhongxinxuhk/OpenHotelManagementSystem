package cn.sec.hn.openbackupsaas.shared.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void keepsWellFormedClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader(RequestIdFilter.HEADER, "req-9f8e7d6c");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);

        assertEquals("req-9f8e7d6c", seenInChain.get());
        assertEquals("req-9f8e7d6c", response.getHeader(RequestIdFilter.HEADER));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "请求结束后必须清理 MDC，避免线程复用串号");
    }

    @Test
    void replacesUnsafeClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader(RequestIdFilter.HEADER, "bad id with spaces and 中文");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        String generated = seenInChain.get();
        assertNotNull(generated);
        assertFalse(generated.contains(" "), "畸形请求 ID 不得进入日志或响应头");
        assertEquals(generated, response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void generatesWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertNotNull(generated);
        assertTrue(generated.length() >= 8);
    }
}
