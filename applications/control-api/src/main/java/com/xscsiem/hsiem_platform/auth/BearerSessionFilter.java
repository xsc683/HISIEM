package com.xscsiem.hsiem_platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 将 PostgreSQL 中的持久化 Bearer 会话转换为 Spring Security Authentication。 */
@Component
public class BearerSessionFilter extends OncePerRequestFilter {

    private final AuthService auth;

    public BearerSessionFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request.getHeader("Authorization"));
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AuthUser user = auth.authenticateToken(token);
            if (user != null) {
                if (user.passwordChangeRequired && requiresPasswordChange(request)) {
                    response.setStatus(428); // Precondition Required
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"请先修改密码\"}");
                    return;
                }
                var authorities = auth.authorities(user).stream()
                        .map(SimpleGrantedAuthority::new).toList();
                // principal 使用用户名而不是 AuthUser 对象,保证 Authentication#getName
                // 在审计、操作人和后台任务中得到稳定的 admin/analyst 字符串。
                var authentication = new UsernamePasswordAuthenticationToken(user.username, token, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean requiresPasswordChange(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/")
                && !path.equals("/api/auth/login")
                && !path.equals("/api/auth/password")
                && !path.equals("/api/auth/me")
                && !path.equals("/api/auth/logout");
    }

    private static String extractToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }
}
