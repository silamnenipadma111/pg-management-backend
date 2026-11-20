package com.pgmanagement.service;

import com.pgmanagement.config.MessagingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends messages outside the website: Email (SMTP), SMS and WhatsApp (Twilio).
 * Runs asynchronously so a slow or failing provider never breaks a booking / payment request.
 * Channels that are not configured are skipped and the message is logged instead.
 */
@Slf4j
@Service
public class MessagingService {

    public record Recipient(String name, String email, String phone) {
    }

    static final String CHANNEL_EMAIL = "EMAIL";
    static final String CHANNEL_SMS = "SMS";
    static final String CHANNEL_WHATSAPP = "WHATSAPP";
    private static final String ACTION_LABEL = "View details";
    private static final int MAX_SMS_LENGTH = 1500;

    private final MessagingProperties props;
    private final MailService mailService;
    private final DemoAccounts demoAccounts;
    private final RestClient twilio;

    public MessagingService(MessagingProperties props, MailService mailService, DemoAccounts demoAccounts,
                            RestClient.Builder restClientBuilder) {
        this.props = props;
        this.mailService = mailService;
        this.demoAccounts = demoAccounts;
        MessagingProperties.Twilio t = props.twilioOrEmpty();
        this.twilio = t.configured()
                ? restClientBuilder.baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + t.accountSid())
                .defaultHeaders(h -> h.setBasicAuth(t.accountSid(), t.authToken())).build()
                : null;
    }

    /** Email + SMS + WhatsApp in the background. */
    @Async
    public void sendAsync(Recipient to, String subject, String text, String link) {
        send(to, subject, text, link);
    }

    /** Email only, in the background (confirmation of an action the recipient performed themselves). */
    @Async
    public void sendEmailAsync(Recipient to, String subject, String text, String link) {
        mailService.send(MailService.Mail.of(to.name(), to.email(), subject, text).withAction(ACTION_LABEL, link));
    }

    /**
     * Sends the message on every configured channel and returns the channels that succeeded.
     *
     * @param link optional absolute URL of the page the message is about
     */
    public List<String> send(Recipient to, String subject, String text, String link) {
        List<String> sent = new ArrayList<>();
        if (demoAccounts.contains(to.email())) {
            log.info("[demo account - not sent] to={} | {}", to.email(), subject);
            return sent;
        }
        if (mailService.send(MailService.Mail.of(to.name(), to.email(), subject, text).withAction(ACTION_LABEL, link))) {
            sent.add(CHANNEL_EMAIL);
        }
        String footer = firstNonBlank(link, props.siteUrl());
        String body = footer == null ? text : text + "\n" + footer;
        String phone = e164(to.phone());
        MessagingProperties.Twilio t = props.twilioOrEmpty();
        if (phone != null && t.smsEnabled() && twilio(t.smsFrom(), phone, subject + ": " + body)) {
            sent.add(CHANNEL_SMS);
        }
        if (phone != null && t.whatsappEnabled()
                && twilio(prefixed(t.whatsappFrom()), "whatsapp:" + phone, "*" + subject + "*\n" + body)) {
            sent.add(CHANNEL_WHATSAPP);
        }
        if (!sent.isEmpty()) {
            log.info("Sent '{}' to {} via {}", subject, to.email(), sent);
        }
        return sent;
    }

    private boolean twilio(String from, String to, String body) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("From", from);
            form.add("To", to);
            form.add("Body", body.length() > MAX_SMS_LENGTH ? body.substring(0, MAX_SMS_LENGTH) : body);
            twilio.post().uri("/Messages.json").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.warn("Twilio message to {} failed: {}", to, ex.getMessage());
            return false;
        }
    }

    private static String prefixed(String from) {
        return from.startsWith("whatsapp:") ? from : "whatsapp:" + from;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    /** Indian 10 digit mobile numbers become +91XXXXXXXXXX; numbers already in +E.164 form are kept. */
    static String e164(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.replaceAll("[\\s-]", "");
        if (p.matches("\\+[1-9]\\d{7,14}")) {
            return p;
        }
        if (p.matches("[6-9]\\d{9}")) {
            return "+91" + p;
        }
        if (p.matches("91[6-9]\\d{9}")) {
            return "+" + p;
        }
        return null;
    }
}
