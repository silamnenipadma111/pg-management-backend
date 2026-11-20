package com.pgmanagement.security;

import com.pgmanagement.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The session cookie: HttpOnly (JavaScript cannot read it), Secure (HTTPS only), SameSite (not sent on
 * requests started by other websites) and limited to the API path.
 */
@Component
@RequiredArgsConstructor
public class SessionCookies {

    static final String PATH = "/api";

    private final AppProperties appProperties;

    public ResponseCookie issue(String token) {
        return build(token, appProperties.auth().sessionTtl());
    }

    /** Tells the browser to delete the cookie. */
    public ResponseCookie clear() {
        return build("", Duration.ZERO);
    }

    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = appProperties.auth().cookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie build(String value, Duration maxAge) {
        AppProperties.Auth auth = appProperties.auth();
        return ResponseCookie.from(auth.cookieName(), value)
                .httpOnly(true)
                .secure(auth.cookieSecure())
                .sameSite(auth.cookieSameSite())
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
