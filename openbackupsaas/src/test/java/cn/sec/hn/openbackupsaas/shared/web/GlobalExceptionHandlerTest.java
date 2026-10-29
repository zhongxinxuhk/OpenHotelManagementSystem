package cn.sec.hn.openbackupsaas.shared.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternals() {
        MDC.put(RequestIdFilter.MDC_KEY, "req-abc12345");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservations");

        ProblemDetail problem = handler.onUnexpected(new IllegalStateException("jdbc: password=hunter2"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), problem.getStatus());
        assertFalse(String.valueOf(problem.getDetail()).contains("password"), "对外文案不得包含内部错误细节");
        assertEquals("req-abc12345", problem.getProperties().get("requestId"));
        assertEquals("/api/v1/reservations", String.valueOf(problem.getInstance()));
    }

    @Test
    void illegalArgumentMapsToBadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/availability");

        ProblemDetail problem = handler.onIllegalArgument(new IllegalArgumentException("checkOut 必须晚于 checkIn"), request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
        assertTrue(String.valueOf(problem.getDetail()).contains("checkOut"));
    }

    @Test
    void requestIdFallsBackToUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/availability");

        ProblemDetail problem = handler.onIllegalArgument(new IllegalArgumentException("x"), request);

        assertNotNull(problem.getProperties().get("requestId"));
        assertEquals("unknown", problem.getProperties().get("requestId"));
    }
}
