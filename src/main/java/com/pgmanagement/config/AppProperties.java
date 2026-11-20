package com.pgmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Application settings (prefix {@code app}). Every value has a safe default so the application starts
 * without any configuration in development.
 *
 * @param publicUrl    base URL of the website (no trailing slash); used for links in emails
 * @param supportEmail business mailbox customers and owners write to; also the reply-to of outgoing mail
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:4200") List<String> corsAllowedOrigins,
        @DefaultValue("./uploads") String uploadDir,
        @DefaultValue("false") boolean seedMockData,
        @DefaultValue("http://localhost:4200") String publicUrl,
        @DefaultValue("support@pg-stay.in") String supportEmail,
        @DefaultValue Auth auth,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Geocoding geocoding) {

    /**
     * Sign-in / session settings.
     *
     * @param sessionTtl               absolute lifetime of a session
     * @param idleTimeout              a session unused for this long is expired
     * @param cookieSecure             send the session cookie over HTTPS only (disable only for local http)
     * @param cookieSameSite           SameSite attribute of the session cookie (Strict, Lax or None)
     * @param requireEmailVerification true, false or auto (= required whenever outgoing mail is configured)
     * @param maxFailedAttempts        failed sign-ins for one account before it is temporarily locked
     * @param lockDuration             how long the account stays locked
     * @param bcryptStrength           BCrypt work factor for new password hashes
     */
    public record Auth(
            @DefaultValue("12h") Duration sessionTtl,
            @DefaultValue("2h") Duration idleTimeout,
            @DefaultValue("PG_SESSION") String cookieName,
            @DefaultValue("true") boolean cookieSecure,
            @DefaultValue("Strict") String cookieSameSite,
            @DefaultValue("auto") String requireEmailVerification,
            @DefaultValue("5") int maxFailedAttempts,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("24h") Duration verificationTtl,
            @DefaultValue("30m") Duration passwordResetTtl,
            @DefaultValue("12") int bcryptStrength) {
    }

    /** Per client IP request limits on the public endpoints (sign-in, sign-up, enquiries, address lookup). */
    public record RateLimit(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Address lookup: coordinates to address ("use my current location") and pincode to city / state.
     *
     * @param baseUrl            a Nominatim compatible API
     * @param pincodeFallbackUrl India Post pincode API, used when the main provider does not know a pincode
     * @param userAgent          identifies this application to the provider (required by its usage policy)
     */
    public record Geocoding(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://nominatim.openstreetmap.org") String baseUrl,
            @DefaultValue("https://api.postalpincode.in") String pincodeFallbackUrl,
            @DefaultValue("PG-Stay/1.0 (https://pg-stay.in)") String userAgent) {
    }

    /** Website URL without a trailing slash. */
    public String siteBaseUrl() {
        String url = publicUrl == null ? "" : publicUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
