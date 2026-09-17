package com.damdam.bot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TossApiProperties.class)
public class HttpClientConfig {

	@Bean
	public RestClient tossRestClient(TossApiProperties properties) {
		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			.build();
	}
}
