package com.momentum.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// /admin/** is excluded from JwtAuthFilter (it's not a per-user Supabase-authenticated route), so
// this is its only gate. Every request under /admin/** must send X-Admin-Key matching
// ADMIN_SECRET_KEY — missing or wrong key is rejected before it reaches any controller, no
// exceptions for any admin path.
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    @Value("${admin.secret-key}")
    private String adminSecretKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String providedKey = request.getHeader(ADMIN_KEY_HEADER);

        if (adminSecretKey == null || adminSecretKey.isBlank()
                || providedKey == null || !constantTimeEquals(providedKey, adminSecretKey)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Missing or invalid X-Admin-Key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    // Avoids leaking timing information about how much of the key matched.
    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
