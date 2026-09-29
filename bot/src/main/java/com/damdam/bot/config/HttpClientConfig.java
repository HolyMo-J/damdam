package com.damdam.bot.config;

import com.damdam.bot.notification.Notifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TossApiProperties.class)
public class HttpClientConfig {

	// 토스 API의 실제 응답 시간은 확인하지 못했다. 정상 응답을 끊지 않을 만큼 넉넉한 값으로 시작한 가정이고, 실측 뒤 조정한다
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	// Boot의 spring.http.clients.* 설정은 자동 구성된 클라이언트에만 적용되고 여기처럼 RestClient.builder()를 직접 부르면
	// 적용되지 않는다(공식 문서에 명시 없음). 그래서 요청 팩토리에 직접 지정한다. 읽기 타임아웃은 JDK HttpClient의
	// HttpRequest.timeout으로 적용된다
	static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return factory;
	}

	@Bean
	public RestClient tossRestClient(TossApiProperties properties, Notifier notifier) {
		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory(CONNECT_TIMEOUT, READ_TIMEOUT))
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
