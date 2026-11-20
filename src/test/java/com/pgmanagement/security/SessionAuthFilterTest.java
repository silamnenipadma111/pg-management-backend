package com.pgmanagement.security;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.security.SessionService.ActiveSession;
import com.pgmanagement.support.TestProps;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionAuthFilterTest {

    private static final String TOKEN = "session-token";
    private static final String API_PATH = "/api/bookings/my";

    @Mock
    private SessionService sessionService;

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aValidSessionCookieAuthenticatesTheRequestWithTheUsersRole() throws Exception {
        AuthUser user = new AuthUser(3L, "anil@example.com", "Anil Varma", Enums.UserRole.CUSTOMER);
        ActiveSession session = new ActiveSession(11L, user, "csrf", LocalDateTime.now().plusHours(1));
        when(sessionService.authenticate(TOKEN)).thenReturn(Optional.of(session));
        MockHttpServletRequest request = apiRequest(API_PATH, TOKEN);

        filter().doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(user);
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        assertThat(request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE)).isSameAs(session);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void anExpiredOrRevokedCookieIsClearedAndTheRequestStaysAnonymous() throws Exception {
        when(sessionService.authenticate(TOKEN)).thenReturn(Optional.empty());
        MockHttpServletRequest request = apiRequest(API_PATH, TOKEN);

        filter().doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("PG_SESSION=;").contains("Max-Age=0");
        assertThat(chain.getRequest()).as("the request still continues to the entry point").isSameAs(request);
    }

    @Test
    void requestsWithoutTheCookieContinueAnonymously() throws Exception {
        MockHttpServletRequest request = apiRequest(API_PATH, null);

        filter().doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(sessionService);
    }

    @Test
    void nonApiPathsAreNotAuthenticatedAtAll() throws Exception {
        MockHttpServletRequest request = apiRequest("/uploads/photo.jpg", TOKEN);

        filter().doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isSameAs(request);
        verifyNoInteractions(sessionService);
    }

    @Test
    void theApiPrefixIsMatchedBelowTheContextPath() throws Exception {
        ActiveSession session = new ActiveSession(11L,
                new AuthUser(5L, "ravi@example.com", "Ravi Kumar", Enums.UserRole.OWNER), "csrf",
                LocalDateTime.now().plusHours(1));
        when(sessionService.authenticate(TOKEN)).thenReturn(Optional.of(session));
        MockHttpServletRequest request = apiRequest("/app/api/owner/pgs", TOKEN);
        request.setContextPath("/app");

        filter().doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_OWNER");
    }

    private SessionAuthFilter filter() {
        return new SessionAuthFilter(sessionService, new SessionCookies(TestProps.app()));
    }

    private static MockHttpServletRequest apiRequest(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            request.setCookies(new Cookie(TestProps.COOKIE_NAME, token));
        }
        return request;
    }
}
