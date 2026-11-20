package com.pgmanagement.controller;

import com.pgmanagement.dto.AuthDtos.*;
import com.pgmanagement.security.SessionAuthFilter;
import com.pgmanagement.security.SessionCookies;
import com.pgmanagement.security.SessionService.ActiveSession;
import com.pgmanagement.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionCookies sessionCookies;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/verify-email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest req) {
        return authService.verifyEmail(req.token());
    }

    @PostMapping("/resend-verification")
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest req) {
        return authService.resendVerification(req.email());
    }

    /** Starts a session: the token is set as an HttpOnly cookie, the body carries the user and the CSRF token. */
    @PostMapping("/signin")
    public ResponseEntity<SessionResponse> signin(@Valid @RequestBody SigninRequest req, HttpServletRequest request) {
        AuthService.SignedIn signedIn = authService.signin(req, request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT), sessionCookies.read(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.issue(signedIn.sessionToken()).toString())
                .body(signedIn.response());
    }

    @GetMapping("/session")
    public SessionResponse session(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) ActiveSession session) {
        return authService.currentSession(session);
    }

    /** Ends the session on the server and removes the cookie. Safe to call when already signed out. */
    @PostMapping("/signout")
    public ResponseEntity<Void> signout(HttpServletRequest request) {
        authService.signout(sessionCookies.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookies.clear().toString())
                .build();
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest req) {
        return authService.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        return authService.resetPassword(req);
    }
}
