package com.pgmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Outbound messaging (Email / SMS / WhatsApp) and rent reminder settings.
 * Every channel is optional: when it is not configured the message is only logged.
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(String siteUrl, String emailFrom, Twilio twilio, Reminders reminders) {

    /** Twilio credentials - one account sends both SMS and WhatsApp messages. */
    public record Twilio(String accountSid, String authToken, String smsFrom, String whatsappFrom) {

        public boolean configured() {
            return notBlank(accountSid) && notBlank(authToken);
        }

        public boolean smsEnabled() {
            return configured() && notBlank(smsFrom);
        }

        public boolean whatsappEnabled() {
            return configured() && notBlank(whatsappFrom);
        }
    }

    /**
     * @param runOnStartup     also check once when the application starts (handy for demos)
     * @param daysBefore       send a reminder this many days before the due date (0 = on the due date)
     * @param overdueEveryDays repeat the reminder every N days while the rent is overdue
     */
    public record Reminders(boolean enabled, boolean runOnStartup, List<Integer> daysBefore, int overdueEveryDays) {
    }

    public Twilio twilioOrEmpty() {
        return twilio == null ? new Twilio(null, null, null, null) : twilio;
    }

    public Reminders remindersOrDefault() {
        return reminders == null ? new Reminders(true, true, List.of(3, 1, 0), 3) : reminders;
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
