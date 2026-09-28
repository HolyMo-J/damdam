package com.damdam.bot.stocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

// 조회 호출 공용 처리: 429는 Retry-After만큼 기다려 한 번만 재시도하고, 그래도 실패하거나 다른 HTTP 오류면 호출자가 정한 조회 예외로 감싼다.
// request 안에서 직접 던진 StockLookupException(응답 본문이 비었다는 판단 등)은 감싸지 않고 그대로 통과시킨다
final class RateLimitRetry {

	private static final Logger log = LoggerFactory.getLogger(RateLimitRetry.class);
	private static final long DEFAULT_RETRY_AFTER_MS = 1000;
	// 서버가 아주 긴 대기를 요구해도 이 이상은 기다리지 않는다. 그 경우 재시도가 또 429로 실패해 조회 실패로 처리된다
	private static final long MAX_RETRY_AFTER_MS = 60_000;

	private RateLimitRetry() {
	}

	// label은 로그용("005930 경고 조회"), wrap은 (실패 설명, 원인)으로 조회 예외를 만드는 함수
	static <T> T call(String label, Supplier<T> request, LongConsumer sleeper,
					  BiFunction<String, Throwable, StockLookupException> wrap) {
		try {
			return request.get();
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() != 429) {
				throw wrap.apply("HTTP " + e.getStatusCode().value(), e);
			}
			long waitMs = readRetryAfterMs(e);
			log.warn("[조회 재시도] {} 호출 제한(429). {}ms 대기 후 재시도합니다.", label, waitMs);
			sleeper.accept(waitMs);
			try {
				return request.get();
			} catch (RestClientException retryFailure) {
				throw wrap.apply("429 재시도 실패", retryFailure);
			}
		} catch (RestClientException e) {
			throw wrap.apply("요청 실패", e);
		}
	}

	static void sleepMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static long readRetryAfterMs(RestClientResponseException e) {
		String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
		if (retryAfter == null) {
			return DEFAULT_RETRY_AFTER_MS;
		}
		try {
			long seconds = Long.parseLong(retryAfter.trim());
			if (seconds < 0) {
				return DEFAULT_RETRY_AFTER_MS;
			}
			return Math.min(seconds, MAX_RETRY_AFTER_MS / 1000) * 1000;
		} catch (NumberFormatException ignored) {
			return DEFAULT_RETRY_AFTER_MS;
		}
	}
}
