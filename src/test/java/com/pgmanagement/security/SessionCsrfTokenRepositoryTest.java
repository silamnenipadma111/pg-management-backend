package com.pgmanagement.security;

import com.pgmanagement.entity.Enums;
import com.pgmanagement.security.SessionService.ActiveSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCsrfTokenRepositoryTest {

    private final SessionCsrfTokenRepository repository = new SessionCsrfTokenRepository();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void theExpectedTokenIsTheOneStoredWithTheSession() {
        ActiveSession session = new ActiveSession(1L,
                new AuthUser(3L, "anil@example.com", "Anil Varma", Enums.UserRole.CUSTOMER), "session-bound-token",
                LocalDateTime.now().plusHours(1));
        request.setAttribute(SessionAuthFilter.SESSION_ATTRIBUTE, session);

        CsrfToken token = repository.loadToken(request);

        assertThat(token.getToken()).isEqualTo("session-bound-token");
        assertThat(token.getHeaderName()).isEqualTo(SessionCsrfTokenRepository.HEADER).isEqualTo("X-CSRF-Token");
        assertThat(token.getParameterName()).isEqualTo("_csrf");
    }

    @Test
    void requestsWithoutASessionHaveNoStoredToken() {
        assertThat(repository.loadToken(request)).isNull();

        request.setAttribute(SessionAuthFilter.SESSION_ATTRIBUTE, "not a session");
        assertThat(repository.loadToken(request)).isNull();
    }

    @Test
    void generatedTokensAreRandomAndNeverWrittenToTheResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken first = repository.generateToken(request);
        CsrfToken second = repository.generateToken(request);
        repository.saveToken(first, request, response);

        assertThat(first.getToken()).hasSize(43).isNotEqualTo(second.getToken());
        assertThat(first.getHeaderName()).isEqualTo(SessionCsrfTokenRepository.HEADER);
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getCookies()).isEmpty();
        assertThat(request.getSession(false)).as("no HTTP session is created").isNull();
    }
}
