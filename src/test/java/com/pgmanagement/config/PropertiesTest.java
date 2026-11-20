package com.pgmanagement.config;

import com.pgmanagement.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesTest {

    @ParameterizedTest
    @CsvSource(value = {
            "https://pg-stay.in, https://pg-stay.in",
            "https://pg-stay.in///, https://pg-stay.in",
            "' https://pg-stay.in/ ', https://pg-stay.in",
            "NULL, ''"}, nullValues = "NULL")
    void siteBaseUrlHasNoTrailingSlash(String configured, String expected) {
        AppProperties props = TestProps.app(configured, "./uploads", TestProps.auth("auto", 5, true), true);

        assertThat(props.siteBaseUrl()).isEqualTo(expected);
    }

    @Test
    void everySettingHasASafeDefault() {
        AppProperties props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app", AppProperties.class);

        assertThat(props.supportEmail()).isEqualTo("support@pg-stay.in");
        assertThat(props.seedMockData()).isFalse();
        assertThat(props.auth().sessionTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(props.auth().idleTimeout()).isEqualTo(Duration.ofHours(2));
        assertThat(props.auth().cookieSecure()).isTrue();
        assertThat(props.auth().cookieSameSite()).isEqualTo("Strict");
        assertThat(props.auth().requireEmailVerification()).isEqualTo("auto");
        assertThat(props.auth().maxFailedAttempts()).isEqualTo(5);
        assertThat(props.rateLimit().enabled()).isTrue();
        assertThat(props.geocoding().enabled()).isTrue();
    }

    @Test
    void twilioChannelsNeedCredentialsAndASender() {
        MessagingProperties.Twilio none = new MessagingProperties.Twilio(null, " ", "+1555", "+1444");
        MessagingProperties.Twilio smsOnly = new MessagingProperties.Twilio("AC1", "secret", "+1555", "");
        MessagingProperties.Twilio both = new MessagingProperties.Twilio("AC1", "secret", "+1555", "+1444");

        assertThat(none.configured()).isFalse();
        assertThat(none.smsEnabled()).isFalse();
        assertThat(smsOnly.smsEnabled()).isTrue();
        assertThat(smsOnly.whatsappEnabled()).isFalse();
        assertThat(both.whatsappEnabled()).isTrue();
    }

    @Test
    void missingMessagingSectionsFallBackToDefaults() {
        MessagingProperties props = new MessagingProperties(null, null, null, null);

        assertThat(props.twilioOrEmpty().configured()).isFalse();
        assertThat(props.remindersOrDefault().enabled()).isTrue();
        assertThat(props.remindersOrDefault().daysBefore()).containsExactly(3, 1, 0);
        assertThat(TestProps.messaging().remindersOrDefault().runOnStartup()).isFalse();
    }
}
