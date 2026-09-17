package com.damdam.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toss.api")
public record TossApiProperties(String baseUrl, String key, String secret, String tokenFilePath) {
}
