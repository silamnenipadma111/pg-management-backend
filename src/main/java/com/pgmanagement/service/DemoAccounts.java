package com.pgmanagement.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email addresses of the seeded demo accounts. Their addresses and phone numbers are made up, so no real
 * email, SMS or WhatsApp message is ever sent to them (the message is only logged).
 */
@Component
public class DemoAccounts {

    private final Set<String> emails = ConcurrentHashMap.newKeySet();

    public void register(Collection<String> demoEmails) {
        demoEmails.forEach(email -> emails.add(normalize(email)));
    }

    public boolean contains(String email) {
        return email != null && emails.contains(normalize(email));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
