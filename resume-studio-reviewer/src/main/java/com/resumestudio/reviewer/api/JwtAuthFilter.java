package com.resumestudio.reviewer.api;

import com.resumestudio.auth.SupabaseJwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Centralised JWT enforcement for all /api/** routes (CQ3).
 * Excluded: /api/health, /api/auth/validate
 * Sets "claims" request attribute for downstream controllers.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final Set<String> EXCLUDED = Set.of(
        "/api/health",
        "/api/auth/validate",
        "/api/jd/preview",       // auth-optional: checked inside controller
        "/api/billing/webhook",  // called by Stripe, no user JWT
        "/api/review/language"   // auth-optional: language analysis
    );

    // SSE streaming — auth checked inside SseReviewController
    private static final String SSE_STREAM_PATH = "/api/review/stream";

    private final SupabaseJwtVerifier verifier;

    public JwtAuthFilter(SupabaseJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (EXCLUDED.contains(path)) return true;
        if (path.equals(SSE_STREAM_PATH)) return true; // auth checked inline
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required.\"}");
            return;
        }
        try {
            SupabaseJwtVerifier.UserClaims claims = verifier.verify(auth.substring(7));
            request.setAttribute("claims", claims);
            chain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("JWT verification failed for {}: {}", request.getRequestURI(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token.\"}");
        }
    }
}
