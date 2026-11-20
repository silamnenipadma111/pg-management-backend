package com.pgmanagement.support;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.config.MessagingProperties;

import java.time.Duration;
import java.util.List;

/** Hand-built configuration records for unit tests (no Spring context needed). */
public final class TestProps {

    public static final String SITE = "https://pg-stay.in";
    public static final String SUPPORT_EMAIL = "support@pg-stay.in";
    public static final String COOKIE_NAME = "PG_SESSION";
    public static final Duration SESSION_TTL = Duration.ofHours(12);
    public static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    public static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    public static final Duration RESET_TTL = Duration.ofMinutes(30);

    private TestProps() {
    }

    public static AppProperties app() {
        return app(auth("false", 5, true));
    }

    public static AppProperties app(AppProperties.Auth auth) {
        return app(SITE, "./uploads", auth, true);
    }

    public static AppProperties app(String publicUrl, String uploadDir, AppProperties.Auth auth, boolean geocoding) {
        return new AppProperties(List.of("http://localhost:4200"), uploadDir, false, publicUrl, SUPPORT_EMAIL, auth,
                new AppProperties.RateLimit(true),
                new AppProperties.Geocoding(geocoding, "https://nominatim.openstreetmap.org",
                        "https://api.postalpincode.in", "PG-Stay-Test/1.0"));
    }

    /**
     * @param emailVerification "true", "false" or "auto"
     */
    public static AppProperties.Auth auth(String emailVerification, int maxFailedAttempts, boolean cookieSecure) {
        return new AppProperties.Auth(SESSION_TTL, IDLE_TIMEOUT, COOKIE_NAME, cookieSecure, "Strict",
                emailVerification, maxFailedAttempts, LOCK_DURATION, VERIFICATION_TTL, RESET_TTL, 4);
    }

    public static MessagingProperties messaging(String siteUrl, String emailFrom, MessagingProperties.Twilio twilio) {
        return new MessagingProperties(siteUrl, emailFrom, twilio,
                new MessagingProperties.Reminders(true, false, List.of(3, 1, 0), 3));
    }

    public static MessagingProperties messaging() {
        return messaging(null, null, null);
    }
}
