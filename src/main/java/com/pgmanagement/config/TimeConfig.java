package com.pgmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    /** Injected wherever "now" matters for security decisions, so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
