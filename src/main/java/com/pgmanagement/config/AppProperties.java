package com.pgmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(List<String> corsAllowedOrigins, String uploadDir, boolean seedMockData) {
}
