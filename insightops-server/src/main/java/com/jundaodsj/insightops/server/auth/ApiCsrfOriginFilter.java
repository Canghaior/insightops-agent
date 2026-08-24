package com.jundaodsj.insightops.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiCsrfOriginFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-InsightOps-CSRF";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private final IdentityProperties properties;
    private final ObjectMapper json;

    public ApiCsrfOriginFilter(IdentityProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || SAFE.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"1".equals(request.getHeader(HEADER))) {
            reject(response, "CSRF_HEADER_REQUIRED", "The anti-CSRF request header is required");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !expectedOrigin().equals(origin)) {
            reject(response, "ORIGIN_NOT_ALLOWED", "The request origin is not allowed");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String expectedOrigin() {
        URI uri = URI.create(properties.getPublicBaseUrl());
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }
}
