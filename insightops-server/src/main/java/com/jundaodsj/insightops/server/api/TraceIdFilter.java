package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.foundation.trace.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "insightops.traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestedTraceId = request.getHeader(TRACE_ID_HEADER);
        String traceId = isAcceptable(requestedTraceId) ? requestedTraceId : TraceId.create().value();

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private boolean isAcceptable(String value) {
        return value != null && !value.isBlank() && value.length() <= 64;
    }
}
