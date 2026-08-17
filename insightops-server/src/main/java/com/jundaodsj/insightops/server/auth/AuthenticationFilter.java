package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "INSIGHTOPS_SESSION";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equals(request.getMethod())
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/system/status")
                || path.startsWith("/actuator/health")
                || path.equals("/actuator/info")
                || path.equals("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = cookie(request);
        var account = authService.authenticate(token);
        if (account.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "UNAUTHENTICATED",
                    "message", "Please sign in"));
            return;
        }
        var authenticated = account.orElseThrow();
        request.setAttribute(CurrentAccount.ATTRIBUTE, authenticated);
        if (authenticated.mustChangePassword() && !isPasswordSetupPath(request.getRequestURI())) {
            response.setStatus(428);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "PASSWORD_CHANGE_REQUIRED",
                    "message", "Change the temporary password before continuing"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPasswordSetupPath(String path) {
        return path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/password")
                || path.equals("/api/v1/auth/logout");
    }

    public static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
