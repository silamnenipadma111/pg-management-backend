package com.pgmanagement.config;

import com.pgmanagement.security.ApiErrorWriter;
import com.pgmanagement.security.FixedWindowRateLimiter;
import com.pgmanagement.security.RateLimitFilter;
import com.pgmanagement.security.SessionAuthFilter;
import com.pgmanagement.security.SessionCookies;
import com.pgmanagement.security.SessionCsrfTokenRepository;
import com.pgmanagement.security.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String H2_CONSOLE = "/h2-console/**";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final int RATE_LIMITER_MAX_KEYS = 100_000;

    private final AppProperties appProperties;
    private final ApiErrorWriter errorWriter;

    /** Development only: the H2 web console needs frames and its own form posts. */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
    public SecurityFilterChain h2ConsoleFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(H2_CONSOLE)
                .csrf(csrf -> csrf.ignoringRequestMatchers(H2_CONSOLE))
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, SessionService sessionService,
                                              SessionCookies sessionCookies, Clock clock) throws Exception {
        // Only requests that carry a session cookie can be forged by another website; they need the CSRF token.
        RequestMatcher sessionWrites = request -> !SAFE_METHODS.contains(request.getMethod())
                && request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) != null;

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new SessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(sessionWrites))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(
                                Duration.ofDays(365).toSeconds())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> errorWriter.write(response,
                                HttpStatus.UNAUTHORIZED, "Please sign in to continue", null))
                        .accessDeniedHandler((request, response, ex) -> {
                            if (ex instanceof CsrfException) {
                                errorWriter.write(response, HttpStatus.FORBIDDEN,
                                        "Your session could not be verified. Please refresh the page and try again.",
                                        "CSRF_TOKEN_INVALID");
                            } else {
                                errorWriter.write(response, HttpStatus.FORBIDDEN, "You are not allowed to do this",
                                        null);
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/signin", "/api/auth/signout",
                                "/api/auth/verify-email", "/api/auth/resend-verification",
                                "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/pgs", "/api/pgs/**", "/api/meta/**", "/api/geo/**",
                                "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/support/requests").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/owner/**").hasRole("OWNER")
                        .requestMatchers("/api/bookings/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/support/**").hasRole("SUPPORT")
                        .requestMatchers("/api/media/**").hasAnyRole("OWNER", "SUPPORT")
                        .anyRequest().authenticated())
                .addFilterBefore(new SessionAuthFilter(sessionService, sessionCookies), CsrfFilter.class)
                .addFilterBefore(new RateLimitFilter(new FixedWindowRateLimiter(clock, RATE_LIMITER_MAX_KEYS),
                        errorWriter, appProperties.rateLimit().enabled()), SessionAuthFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(appProperties.auth().bcryptStrength());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Normalise env values: trim spaces and drop trailing slashes ("https://pg-stay.in/" never matches an Origin header).
        List<String> origins = appProperties.corsAllowedOrigins().stream()
                .map(String::trim)
                .map(SecurityConfig::withoutTrailingSlashes)
                .filter(origin -> !origin.isEmpty())
                .toList();
        log.info("CORS allowed origins: {}", origins);
        config.setAllowedOriginPatterns(origins);
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "Accept-Language", SessionCsrfTokenRepository.HEADER));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // The session cookie is sent on cross-origin calls from the website (e.g. www. -> api.) only when allowed here.
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static String withoutTrailingSlashes(String origin) {
        String value = origin;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
