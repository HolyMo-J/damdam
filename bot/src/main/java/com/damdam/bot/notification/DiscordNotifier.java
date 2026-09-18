package com.damdam.bot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// 디스코드 웹훅으로 알림을 보낸다. 웹훅 주소에는 토큰이 들어 있으므로 비밀값으로 취급한다:
// .env에서만 읽고, 로그와 예외 메시지에 절대 남기지 않는다 (HTTP 클라이언트 예외 메시지에는 요청 URL이 들어가므로 메시지 대신 종류나 상태 코드만 기록)
@Component
class DiscordNotifier implements Notifier {

	private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
	// 다른 주소로 알림이 새어 나가지 않게, 디스코드 웹훅 형식만 허용한다
	private static final String WEBHOOK_PREFIX = "https://discord.com/api/webhooks/";
	private static final int MAX_LENGTH = 1900;

	private final RestClient restClient;
	private final String webhookUrl;
	private final boolean enabled;
	private final Duration throttle;
	private final Clock clock;
	private final Executor executor;
	private final Map<String, Instant> lastSentAt = new HashMap<>();

	@Autowired
	DiscordNotifier(
			@Value("${damdam.notification.discord-webhook-url:}") String webhookUrl,
			@Value("${damdam.notification.throttle-seconds:600}") long throttleSeconds) {
		this(webhookUrl, RestClient.builder(), Duration.ofSeconds(throttleSeconds), Clock.systemUTC(),
			Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "discord-notifier");
				thread.setDaemon(true);
				return thread;
			}));
	}

	DiscordNotifier(String webhookUrl, RestClient.Builder builder, Duration throttle, Clock clock, Executor executor) {
		this.webhookUrl = webhookUrl;
		this.restClient = builder.build();
		this.throttle = throttle;
		this.clock = clock;
		this.executor = executor;
		if (webhookUrl == null || webhookUrl.isBlank()) {
			this.enabled = false;
			log.info("디스코드 웹훅 주소가 설정되지 않아 알림을 보내지 않습니다.");
		} else if (!webhookUrl.startsWith(WEBHOOK_PREFIX)) {
			this.enabled = false;
			log.warn("디스코드 웹훅 주소 형식이 올바르지 않아 알림을 비활성화합니다. (값은 보안상 출력하지 않습니다)");
		} else {
			this.enabled = true;
		}
	}

	@Override
	public void send(String eventKey, String message) {
		if (!enabled || !shouldSend(eventKey)) {
			return;
		}
		executor.execute(() -> post(message));
	}

	@Override
	public void sendNow(String message) {
		if (enabled) {
			post(message);
		}
	}

	private synchronized boolean shouldSend(String eventKey) {
		Instant now = clock.instant();
		Instant previous = lastSentAt.get(eventKey);
		if (previous != null && now.isBefore(previous.plus(throttle))) {
			return false;
		}
		lastSentAt.put(eventKey, now);
		return true;
	}

	private void post(String message) {
		String content = message.length() > MAX_LENGTH ? message.substring(0, MAX_LENGTH) : message;
		try {
			restClient.post()
				.uri(URI.create(webhookUrl))
				.contentType(MediaType.APPLICATION_JSON)
				// 메시지 안의 @everyone 같은 멘션이 실제로 동작하지 않게 막는다
				.body(Map.of("content", content, "allowed_mentions", Map.of("parse", List.of())))
				.retrieve()
				.toBodilessEntity();
		} catch (RestClientResponseException e) {
			log.warn("디스코드 알림 전송 실패: HTTP {}", e.getStatusCode().value());
		} catch (Exception e) {
			log.warn("디스코드 알림 전송 실패: {}", e.getClass().getSimpleName());
		}
	}
}
