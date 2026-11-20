package com.pgmanagement.service;

import com.pgmanagement.config.AppProperties;
import com.pgmanagement.config.MessagingProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sends the branded transactional emails (HTML with a plain-text alternative) through SMTP.
 * Mail is sent from the configured mailbox and replies go to the business support address.
 * When SMTP is not configured nothing is sent and the message is written to the log instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    /**
     * @param actionUrl optional absolute link rendered as a button
     * @param replyTo   optional reply-to address; defaults to the support mailbox
     */
    public record Mail(String toName, String toEmail, String subject, List<String> paragraphs, String actionLabel,
                       String actionUrl, String replyTo) {

        public static Mail of(String toName, String toEmail, String subject, String... paragraphs) {
            return new Mail(toName, toEmail, subject, List.of(paragraphs), null, null, null);
        }

        public Mail withAction(String label, String url) {
            return new Mail(toName, toEmail, subject, paragraphs, label, url, replyTo);
        }

        public Mail withReplyTo(String address) {
            return new Mail(toName, toEmail, subject, paragraphs, actionLabel, actionUrl, address);
        }
    }

    private static final String BRAND = "PG-STAY.IN";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessagingProperties messagingProperties;
    private final AppProperties appProperties;
    private final DemoAccounts demoAccounts;

    /** True when an SMTP host is configured, i.e. emails really leave the application. */
    public boolean isConfigured() {
        return configuredSender() != null;
    }

    @Async
    public void sendAsync(Mail mail) {
        send(mail);
    }

    /** @return true when the mail server accepted the message */
    public boolean send(Mail mail) {
        if (isBlank(mail.toEmail())) {
            return false;
        }
        if (demoAccounts.contains(mail.toEmail())) {
            log.info("[demo account - not sent] to={} | {}", mail.toEmail(), mail.subject());
            return false;
        }
        JavaMailSender sender = configuredSender();
        if (sender == null) {
            log.info("[mail not configured] to={} | {} | {} | link={}", mail.toEmail(), mail.subject(),
                    String.join(" ", mail.paragraphs()), mail.actionUrl());
            return false;
        }
        try {
            sender.send(compose(sender, mail));
            log.info("Sent email '{}' to {}", mail.subject(), mail.toEmail());
            return true;
        } catch (MessagingException | UnsupportedEncodingException | MailException ex) {
            log.warn("Email '{}' to {} failed: {}", mail.subject(), mail.toEmail(), ex.getMessage());
            return false;
        }
    }

    private MimeMessage compose(JavaMailSender sender, Mail mail)
            throws MessagingException, UnsupportedEncodingException {
        String support = appProperties.supportEmail();
        String name = isBlank(mail.toName()) ? "there" : mail.toName();
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(isBlank(messagingProperties.emailFrom()) ? support : messagingProperties.emailFrom(), BRAND);
        helper.setTo(mail.toEmail().trim());
        helper.setReplyTo(isBlank(mail.replyTo()) ? support : mail.replyTo().trim());
        helper.setSubject(BRAND + " - " + singleLine(mail.subject()));
        helper.setText(EmailTemplates.text(name, mail.paragraphs(), mail.actionUrl(), support),
                EmailTemplates.html(name, mail.paragraphs(), mail.actionLabel(), mail.actionUrl(), support,
                        appProperties.siteBaseUrl()));
        return message;
    }

    private JavaMailSender configuredSender() {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender instanceof JavaMailSenderImpl impl && isBlank(impl.getHost())) {
            return null;
        }
        return sender;
    }

    /** Header values must never contain line breaks. */
    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
