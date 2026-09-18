package com.damdam.bot.config;

import com.damdam.bot.notification.Notifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TossApiProperties.class)
public class HttpClientConfig {

	@Bean
	public RestClient tossRestClient(TossApiProperties properties, Notifier notifier) {
		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			// 모든 토스 API 호출이 이 클라이언트를 거치므로, 403(허용 IP 미등록) 감지를 한 곳에서 처리한다
			.requestInterceptor((request, body, execution) -> {
				ClientHttpResponse response = execution.execute(request, body);
				if (response.getStatusCode().value() == 403) {
					notifier.send("toss-403", "[담담] 토스 API가 403을 반환했습니다. 공인 IP가 바뀌었을 수 있습니다. WTS 설정의 허용 IP 관리에서 다시 등록하세요.");
				}
				return response;
			})
			.build();
	}
}
