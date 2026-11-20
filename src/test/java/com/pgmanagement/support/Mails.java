package com.pgmanagement.support;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the interesting parts of a composed email. */
public final class Mails {

    private static final Pattern TOKEN = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private Mails() {
    }

    /** What a mail sender hands to the application when asked for a new message. */
    public static MimeMessage blank() {
        return new MimeMessage((Session) null);
    }

    public static String plainText(MimeMessage message) {
        return body(message, "text/plain");
    }

    public static String html(MimeMessage message) {
        return body(message, "text/html");
    }

    public static String to(MimeMessage message) {
        try {
            return address(message.getRecipients(Message.RecipientType.TO));
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String replyTo(MimeMessage message) {
        try {
            return address(message.getReplyTo());
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InternetAddress from(MimeMessage message) {
        try {
            return (InternetAddress) message.getFrom()[0];
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The token of the "verify email" / "reset password" link inside the mail. */
    public static String linkToken(MimeMessage message) {
        Matcher matcher = TOKEN.matcher(plainText(message));
        if (!matcher.find()) {
            throw new AssertionError("No token link in mail: " + plainText(message));
        }
        return matcher.group(1);
    }

    private static String address(Address[] addresses) {
        return addresses == null || addresses.length == 0 ? null : ((InternetAddress) addresses[0]).getAddress();
    }

    private static String body(MimeMessage message, String mimeType) {
        try {
            // headers such as Content-Type are only written when the message is "saved"
            message.saveChanges();
            return find(message, mimeType);
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String find(Part part, String mimeType) throws MessagingException, IOException {
        if (part.isMimeType(mimeType)) {
            return (String) part.getContent();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = find(multipart.getBodyPart(i), mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
