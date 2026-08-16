package com.xscsiem.hsiem_platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 控制台鉴权(story-08):保护敏感操作,要求有效会话 + admin 角色。
 * 保护范围(MVP,增量扩大):
 * - /api/settings/** 的写操作(POST/PUT/DELETE:改关键度/触发重算)
 * - /api/detection-rules/deploy(POST:重启检测 job)
 * - /api/auth/users|roles|audit-logs(全部,用户/角色/审计管理)
 * 只读视图(模板/数据源/规则/健康 GET)保持开放,避免未登录即全屏失效。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean adminWrite = "POST".equals(method) && path.equals("/api/detection-rules/deploy");
        boolean settingsWrite = (method.equals("POST") || method.equals("PUT") || method.equals("DELETE"))
                && path.startsWith("/api/settings/");
        boolean authMgmt = path.startsWith("/api/auth/users") || path.startsWith("/api/auth/roles")
                || path.startsWith("/api/auth/audit-logs");
        if (!adminWrite && !settingsWrite && !authMgmt) {
            return true;
        }
        String token = extractToken(request.getHeader("Authorization"));
        try {
            AuthUser u = auth.me(token);
            if (!auth.isAdmin(u)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "需要 admin 权限");
                return false;
            }
            return true;
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话过期");
            return false;
        }
    }

    private static String extractToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
    }
}
