package com.pgmanagement.service;

import com.pgmanagement.dto.AuthDtos.*;
import com.pgmanagement.entity.Enums;
import com.pgmanagement.entity.User;
import com.pgmanagement.exception.ApiException;
import com.pgmanagement.repository.UserRepository;
import com.pgmanagement.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        if (req.role() == Enums.UserRole.SUPPORT) {
            throw ApiException.badRequest("Customer care accounts cannot be created via signup");
        }
        User user = createUser(req.name(), req.email(), req.phone(), req.password(), req.role(), req.gender());
        return new AuthResponse(jwtService.generateToken(user), DtoMapper.toUserDto(user));
    }

    @Transactional
    public User createUser(String name, String email, String phone, String rawPassword, Enums.UserRole role,
                           Enums.Gender gender) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setName(name.trim());
        user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        user.setPhone(phone.trim());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        user.setGender(gender);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse signin(SigninRequest req) {
        User user = userRepository.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (req.role() != null && req.role() != user.getRole()) {
            throw new BadCredentialsException("This account is registered as " + user.getRole().name().toLowerCase(Locale.ROOT)
                    + ". Please sign in as " + user.getRole().name().toLowerCase(Locale.ROOT) + ".");
        }
        return new AuthResponse(jwtService.generateToken(user), DtoMapper.toUserDto(user));
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return userRepository.findById(userId).map(DtoMapper::toUserDto)
                .orElseThrow(() -> ApiException.notFound("User"));
    }
}
