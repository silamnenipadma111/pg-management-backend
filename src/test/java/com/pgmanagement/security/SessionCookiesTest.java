package com.pgmanagement.security;

import com.pgmanagement.support.TestProps;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCookiesTest {

    private static final String TOKEN = "session-token";

    private final SessionCookies cookies = new SessionCookies(TestProps.app());

    @Test
    void theSessionCookieIsHiddenFromScriptsAndLimitedToHttpsSameSiteAndTheApiPath() {
        ResponseCookie cookie = cookies.issue(TOKEN);

        assertThat(cookie.getName()).isEqualTo(TestProps.COOKIE_NAME);
        assertThat(cookie.getValue()).isEqualTo(TOKEN);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo(SessionCookies.PATH).isEqualTo("/api");
        assertThat(cookie.getMaxAge()).isEqualTo(TestProps.SESSION_TTL);
        assertThat(cookie.getDomain()).isNull();
    }

    @Test
    void theHeaderValueCarriesEveryAttribute() {
        assertThat(cookies.issue(TOKEN).toString())
                .startsWith("PG_SESSION=session-token")
                .contains("Path=/api", "Max-Age=43200", "Secure", "HttpOnly", "SameSite=Strict");
    }

    @Test
    void clearingExpiresTheCookieImmediatelyWithTheSameAttributes() {
        ResponseCookie cookie = cookies.clear();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.getPath()).isEqualTo(SessionCookies.PATH);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.toString()).contains("Max-Age=0");
    }

    @Test
    void theSecureFlagCanBeSwitchedOffForLocalHttp() {
        SessionCookies localCookies = new SessionCookies(TestProps.app(TestProps.auth("false", 5, false)));

        assertThat(localCookies.issue(TOKEN).isSecure()).isFalse();
        assertThat(localCookies.issue(TOKEN).isHttpOnly()).isTrue();
    }

    @Test
    void readsTheSessionCookieAmongOtherCookies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("theme", "dark"), new Cookie(TestProps.COOKIE_NAME, TOKEN));

        assertThat(cookies.read(request)).isEqualTo(TOKEN);
    }

    @Test
    void readReturnsNothingWhenTheCookieIsMissingOrBlank() {
        MockHttpServletRequest withoutCookies = new MockHttpServletRequest();
        MockHttpServletRequest otherCookies = new MockHttpServletRequest();
        otherCookies.setCookies(new Cookie("theme", "dark"));
        MockHttpServletRequest blankCookie = new MockHttpServletRequest();
        blankCookie.setCookies(new Cookie(TestProps.COOKIE_NAME, " "));

        assertThat(cookies.read(withoutCookies)).isNull();
        assertThat(cookies.read(otherCookies)).isNull();
        assertThat(cookies.read(blankCookie)).isNull();
    }
}
