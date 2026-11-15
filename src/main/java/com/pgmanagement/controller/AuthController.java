package com.pgmanagement.controller;

import com.pgmanagement.dto.AuthDtos.*;
import com.pgmanagement.security.AuthUser;
import com.pgmanagement.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/signin")
    public AuthResponse signin(@Valid @RequestBody SigninRequest req) {
        return authService.signin(req);
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user.id());
    }
}
