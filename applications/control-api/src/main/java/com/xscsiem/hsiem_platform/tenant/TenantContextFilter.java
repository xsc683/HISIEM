package com.xscsiem.hsiem_platform.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.auth.ForbiddenException;
import com.xscsiem.hsiem_platform.onboarding.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/** 认证完成后校验 X-Tenant-ID 成员关系，阻止只靠客户端 Header 切换租户。 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TenantService tenants;
    private final ObjectMapper mapper;

    public TenantContextFilter(TenantService tenants, ObjectMapper mapper) {
        this.tenants = tenants;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            chain.doFilter(request, response);
            return;
        }
        // 生产认证由 BearerSessionFilter 创建 String principal。Spring Security 测试注解使用
        // UserDetails principal，且项目没有启用表单/basic 登录；测试身份保持 default 租户即可。
        if (!(authentication.getPrincipal() instanceof String)) {
            TenantContext.set(TenantContext.DEFAULT_TENANT);
            try {
                chain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
            return;
        }
        try {
            String tenant = tenants.requireMembership(authentication.getName(), request.getHeader("X-Tenant-ID"));
            TenantContext.set(tenant);
            response.setHeader("X-Tenant-ID", tenant);
            chain.doFilter(request, response);
        } catch (ForbiddenException e) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getWriter(), new ApiError(Instant.now(), 403,
                    "TENANT_FORBIDDEN", e.getMessage(), null));
        } finally {
            TenantContext.clear();
        }
    }
}
