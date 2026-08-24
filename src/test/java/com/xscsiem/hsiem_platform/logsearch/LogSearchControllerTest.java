package com.xscsiem.hsiem_platform.logsearch;

import com.xscsiem.hsiem_platform.onboarding.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
class LogSearchControllerTest {

    @Test
    void unavailableError_isReturnedAs503WithoutLeakingElasticsearchDetails() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/log-search");

        var response = advice.logSearchUnavailable(
                new LogSearchUnavailableException("日志检索服务暂不可用"), request);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("LOG_SEARCH_UNAVAILABLE", response.getBody().code());
        assertEquals("/api/log-search", response.getBody().path());
    }
}
