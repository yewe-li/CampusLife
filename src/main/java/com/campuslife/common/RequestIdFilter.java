package com.campuslife.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Generate on the server; do not trust unbounded client-supplied log content.
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith("/api/auth") || request.getRequestURI().startsWith("/api/me")
                || request.getRequestURI().startsWith("/api/merchant")
                || request.getRequestURI().endsWith("/claims")) response.setHeader("Cache-Control", "no-store");
        try { chain.doFilter(request, response); } finally { MDC.remove("requestId"); }
    }
}
