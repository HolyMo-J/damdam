package com.damdam.bot.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 디스코드로는 아무것도 보내지 않는다. MockRestServiceServer가 요청을 가로채서 주소와 본문만 검증한다
class DiscordNotifierTest {

	private static final String SECRET_TOKEN = "SECRET-TOKEN-VALUE";
	private static final String WEBHOOK = "https://discord.com/api/webhooks/123456/" + SECRET_TOKEN;

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private RestClient.Builder builder;
	private MockRestServiceServer server;
	private MutableClock clock;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		clock = new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
		logs.start();
		((Logger) LoggerFactory.getLogger(DiscordNotifier.class)).addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(DiscordNotifier.class)).detachAppender(logs);
	}

	private DiscordNotifier notifier(String url) {
		return new DiscordNotifier(url, builder, Duration.ofMinutes(10), clock, Runnable::run);
	}

	@Test
	void sendsTheMessageAsJsonToTheWebhook() {
		server.expect(requestTo(WEBHOOK))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.content").value("안전장치 발동"))
			.andExpect(jsonPath("$.allowed_mentions.parse").isEmpty())
			.andRespond(withSuccess());

		notifier(WEBHOOK).send("guard", "안전장치 발동");

		server.verify();
	}

	@Test
	void doesNothingWhenNoWebhookIsConfigured() {
		notifier("").send("guard", "보내지면 안 되는 메시지");

		server.verify();
	}

	// 다른 주소로 알림이 새지 않게, 디스코드 웹훅 형식이 아니면 비활성화한다
	@Test
	void refusesUrlsThatAreNotDiscordWebhooks() {
		notifier("https://evil.example.com/steal").send("guard", "보내지면 안 되는 메시지");

		server.verify();
		assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("evil.example.com")));
	}

	@Test
	void sameEventIsThrottledButDifferentEventsAndLaterRepeatsGoThrough() {
		server.expect(requestTo(WEBHOOK)).andRespond(withSuccess());
		server.expect(requestTo(WEBHOOK)).andRespond(withSuccess());
		server.expect(requestTo(WEBHOOK)).andRespond(withSuccess());
		DiscordNotifier notifier = notifier(WEBHOOK);

		notifier.send("oco-fail", "첫 번째");
		notifier.send("oco-fail", "같은 사건 반복: 무시돼야 함");
		notifier.send("guard", "다른 사건: 전송돼야 함");
		clock.advance(Duration.ofMinutes(11));
		notifier.send("oco-fail", "시간이 지난 뒤 반복: 전송돼야 함");

		server.verify();
	}

	@Test
	void truncatesVeryLongMessages() {
		server.expect(requestTo(WEBHOOK))
			.andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.hasLength(1900)))
			.andRespond(withSuccess());

		notifier(WEBHOOK).send("long", "가".repeat(5000));

		server.verify();
	}

	// 전송이 실패해도 예외를 던지지 않고(매매 경로를 막지 않음), 로그에 웹훅 주소나 토큰이 남지 않는다
	@Test
	void failureNeverThrowsAndNeverLogsTheWebhookUrl() {
		server.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertDoesNotThrow(() -> notifier(WEBHOOK).send("guard", "메시지"));

		assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("429")));
		assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(SECRET_TOKEN)));
		assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("discord.com")));
	}

	@Test
	void sendNowIsNotThrottled() {
		server.expect(requestTo(WEBHOOK)).andRespond(withSuccess());
		server.expect(requestTo(WEBHOOK)).andRespond(withSuccess());
		DiscordNotifier notifier = notifier(WEBHOOK);

		notifier.sendNow("봇 종료");
		notifier.sendNow("봇 종료");

		server.verify();
	}

	private static final class MutableClock extends Clock {
		private Instant now;

		MutableClock(Instant start) {
			this.now = start;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
