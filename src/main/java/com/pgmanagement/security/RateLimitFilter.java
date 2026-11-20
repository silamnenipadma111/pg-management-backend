package com.pgmanagement.security;

import com.pgmanagement.exception.ApiError;
import com.pgmanagement.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Limits how often one client (IP address) may call the public endpoints that are attractive for abuse:
 * password guessing, mass sign-ups, mail bombing through "resend" / "forgot password", enquiry spam and
 * the address lookup that calls an external provider.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    record Rule(String method, String path, int limit, Duration window) {

        boolean matches(String requestMethod, String requestPath) {
            return method.equals(requestMethod) && requestPath.startsWith(path);
        }
    }

    static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/auth/signin", 30, Duration.ofMinutes(5)),
            new Rule("POST", "/api/auth/signup", 20, Duration.ofHours(1)),
            new Rule("POST", "/api/auth/resend-verification", 5, Duration.ofMinutes(15)),
            new Rule("POST", "/api/auth/forgot-password", 5, Duration.ofMinutes(15)),
            new Rule("POST", "/api/auth/reset-password", 10, Duration.ofMinutes(15)),
            new Rule("POST", "/api/auth/verify-email", 20, Duration.ofMinutes(15)),
            new Rule("POST", "/api/support/requests", 5, Duration.ofMinutes(15)),
            new Rule("GET", "/api/geo/", 60, Duration.ofMinutes(1)));

    private final FixedWindowRateLimiter limiter;
    private final ApiErrorWriter errorWriter;
    private final boolean enabled;

    public RateLimitFilter(FixedWindowRateLimiter limiter, ApiErrorWriter errorWriter, boolean enabled) {
        this.limiter = limiter;
        this.errorWriter = errorWriter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Rule rule : RULES) {
            if (rule.matches(request.getMethod(), path)) {
                long retryAfter = limiter.tryAcquire(rule.path() + '|' + request.getRemoteAddr(), rule.limit(),
                        rule.window());
                if (retryAfter > 0) {
                    errorWriter.write(response, ApiError.of(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many requests. Please try again in a little while.",
                            ApiException.CODE_TOO_MANY_ATTEMPTS, retryAfter));
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }
}
