package com.pgmanagement.security;

import com.pgmanagement.security.SessionService.ActiveSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Authenticates API requests from the session cookie. */
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link ActiveSession}; its presence means "authenticated by cookie". */
    public static final String SESSION_ATTRIBUTE = "com.pgmanagement.security.session";

    private static final String API_PREFIX = "/api/";

    private final SessionService sessionService;
    private final SessionCookies sessionCookies;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = sessionCookies.read(request);
        if (token != null) {
            Optional<ActiveSession> session = sessionService.authenticate(token);
            if (session.isPresent()) {
                signIn(request, session.get());
            } else {
                // expired or revoked: make the browser drop the stale cookie
                response.addHeader(HttpHeaders.SET_COOKIE, sessionCookies.clear().toString());
            }
        }
        chain.doFilter(request, response);
    }

    private static void signIn(HttpServletRequest request, ActiveSession session) {
        AuthUser user = session.user();
        var authentication = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.setAttribute(SESSION_ATTRIBUTE, session);
    }
}
