package com.xscsiem.hsiem_platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xscsiem.hsiem_platform.onboarding.ApiError;
import com.xscsiem.hsiem_platform.tenant.TenantContextFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/** 阶段 4.2 安全边界:无状态 HTTP 层 + PostgreSQL 持久化 Bearer 会话 + 方法级权限。 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    ObjectMapper apiObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** 项目使用自定义 Bearer 会话，不启用 Boot 的随机密码用户。 */
    @Bean
    UserDetailsService unusedFormUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("未使用表单登录: " + username);
        };
    }

    /**
     * 内部服务到服务链:SOC Copilot 触发 SOAR 执行。必须排在用户会话链之前, 只匹配 {@code /api/internal/**},不要求用户成员关系,不落回用户链。
     */
    @Bean
    @Order(1)
    SecurityFilterChain internalServiceSecurity(
            HttpSecurity http,
            @Value("${app.internal-service.token:}") String internalServiceToken,
            ObjectMapper mapper)
            throws Exception {
        http.securityMatcher("/api/internal/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL_SERVICE"))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                jsonError(
                                                        mapper,
                                                        HttpServletResponse.SC_UNAUTHORIZED,
                                                        "UNAUTHORIZED",
                                                        "未授权"))
                                        .accessDeniedHandler(jsonDenied(mapper)))
                .addFilterBefore(
                        new InternalServiceAuthFilter(internalServiceToken, mapper),
                        AnonymousAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            BearerSessionFilter bearerSessionFilter,
            TenantContextFilter tenantContextFilter,
            ObjectMapper mapper)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/auth/login",
                                                "/actuator/health",
                                                "/actuator/info",
                                                "/hello")
                                        .permitAll()
                                        .requestMatchers("/actuator/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                jsonError(
                                                        mapper,
                                                        HttpServletResponse.SC_UNAUTHORIZED,
                                                        "UNAUTHORIZED",
                                                        "未登录或会话已过期"))
                                        .accessDeniedHandler(jsonDenied(mapper)))
                .addFilterBefore(bearerSessionFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, BearerSessionFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }

    private AuthenticationEntryPoint jsonError(
            ObjectMapper mapper, int status, String code, String message) {
        return (request, response, exception) ->
                writeError(mapper, response, status, code, message);
    }

    private AccessDeniedHandler jsonDenied(ObjectMapper mapper) {
        return (request, response, exception) ->
                writeError(
                        mapper,
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        "FORBIDDEN",
                        "当前角色无权执行该操作");
    }

    private void writeError(
            ObjectMapper mapper,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(
                response.getWriter(), new ApiError(Instant.now(), status, code, message, null));
    }
}
