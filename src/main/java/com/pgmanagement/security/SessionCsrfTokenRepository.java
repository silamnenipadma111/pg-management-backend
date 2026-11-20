package com.pgmanagement.security;

import com.pgmanagement.security.SessionService.ActiveSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/**
 * CSRF tokens are bound to the server-side session (synchronizer token pattern): the website receives the token
 * in the sign-in / session response and must echo it in the {@value #HEADER} header of every state changing
 * request. Another website can make the browser send the cookie, but it can never read this token.
 */
public class SessionCsrfTokenRepository implements CsrfTokenRepository {

    public static final String HEADER = "X-CSRF-Token";
    private static final String PARAMETER = "_csrf";

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        // Only reached for requests without a session, which are never CSRF protected (nothing to protect).
        return new DefaultCsrfToken(HEADER, PARAMETER, SecureTokens.random());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        // The token lives in the user_sessions row and is created together with the session.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        if (request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) instanceof ActiveSession session) {
            return new DefaultCsrfToken(HEADER, PARAMETER, session.csrfToken());
        }
        return null;
    }
}
