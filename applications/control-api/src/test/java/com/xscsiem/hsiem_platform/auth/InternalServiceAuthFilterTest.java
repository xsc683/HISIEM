package com.xscsiem.hsiem_platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link InternalServiceAuthFilter} 的 fail-closed 与租户派生语义。 */
class InternalServiceAuthFilterTest {

    private static final String TOKEN = "internal-secret";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void blankConfiguredTokenRejectsEverything() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalServiceAuthFilter("", MAPPER)
                .doFilter(
                        request("Bearer anything", "default"),
                        response,
                        recording(invoked, new AtomicReference<>()));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
    }

    @Test
    void wrongBearerIsRejected() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalServiceAuthFilter(TOKEN, MAPPER)
                .doFilter(
                        request("Bearer not-the-token", "default"),
                        response,
                        recording(invoked, new AtomicReference<>()));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
    }

    @Test
    void missingBearerIsRejected() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalServiceAuthFilter(TOKEN, MAPPER)
                .doFilter(
                        request(null, "default"),
                        response,
                        recording(invoked, new AtomicReference<>()));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
    }

    @Test
    void missingTenantIsRejected() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalServiceAuthFilter(TOKEN, MAPPER)
                .doFilter(
                        request("Bearer " + TOKEN, null),
                        response,
                        recording(invoked, new AtomicReference<>()));

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
    }

    @Test
    void validTokenDerivesTenantAndAuthorityThenClears() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        AtomicReference<String> tenantDuringChain = new AtomicReference<>();
        AtomicReference<Authentication> authDuringChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new InternalServiceAuthFilter(TOKEN, MAPPER)
                .doFilter(
                        request("Bearer " + TOKEN, "tenant-a"),
                        response,
                        (req, res) -> {
                            invoked.set(true);
                            tenantDuringChain.set(TenantContext.id());
                            authDuringChain.set(
                                    SecurityContextHolder.getContext().getAuthentication());
                        });

        assertTrue(invoked.get());
        assertEquals("tenant-a", tenantDuringChain.get());
        assertTrue(
                authDuringChain.get().getAuthorities().stream()
                        .anyMatch(
                                authority ->
                                        InternalServiceAuthFilter.INTERNAL_ROLE.equals(
                                                authority.getAuthority())));
        // 请求结束后必须清理线程状态,避免泄漏到复用线程。
        assertEquals(TenantContext.DEFAULT_TENANT, TenantContext.id());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(response.getContentAsString().contains(TOKEN));
    }

    private static FilterChain recording(AtomicBoolean invoked, AtomicReference<String> tenant) {
        return (req, res) -> {
            invoked.set(true);
            tenant.set(TenantContext.id());
        };
    }

    private static MockHttpServletRequest request(String authorization, String tenant) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/internal/soar/executions");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (tenant != null) {
            request.addHeader("X-Tenant-ID", tenant);
        }
        return request;
    }
}
