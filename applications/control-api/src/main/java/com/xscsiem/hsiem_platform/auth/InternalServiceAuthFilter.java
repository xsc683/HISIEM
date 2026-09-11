package com.xscsiem.hsiem_platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.ApiError;
import com.xscsiem.hsiem_platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * 内部服务到服务认证(SOC Copilot → HISIEM 内部 SOAR 触发入口)。
 *
 * <p>只由 {@code /api/internal/**} 专用 SecurityFilterChain 装配。故意不是 {@code @Component}:
 * 否则 Spring Boot 会把它注册成全局 Servlet Filter,绕过链的匹配范围。Bearer 与配置凭据做常量时间比较;
 * 配置凭据为空、Bearer 缺失/错误或 {@code X-Tenant-ID} 为空时必须全部拒绝(fail closed),
 * 且不向调用方暴露失败细节。成功后设置 {@code ROLE_INTERNAL_SERVICE} 并使用服务端 Header 派生租户。</p>
 */
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    public static final String INTERNAL_PRINCIPAL = "internal-service";
    public static final String INTERNAL_ROLE = "ROLE_INTERNAL_SERVICE";

    private final byte[] expectedToken;
    private final ObjectMapper mapper;

    public InternalServiceAuthFilter(String configuredToken, ObjectMapper mapper) {
        this.expectedToken = configuredToken == null
                ? new byte[0]
                : configuredToken.trim().getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request.getHeader("Authorization"));
        String tenant = request.getHeader("X-Tenant-ID");
        if (expectedToken.length == 0
                || token == null
                || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))
                || tenant == null
                || tenant.isBlank()) {
            writeUnauthorized(response);
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                INTERNAL_PRINCIPAL, null, List.of(new SimpleGrantedAuthority(INTERNAL_ROLE)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TenantContext.set(tenant.trim());
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(),
                new ApiError(Instant.now(), HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", "未授权", null));
    }

    private static String extractToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }
}
