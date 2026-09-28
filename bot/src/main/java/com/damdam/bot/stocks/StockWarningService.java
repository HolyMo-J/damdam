package com.damdam.bot.stocks;

import com.damdam.bot.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.function.LongConsumer;

// 매수 유의사항(GET /api/v1/stocks/{symbol}/warnings) 조회. 조회 전용이고 STOCK 그룹(초당 5회)이다.
// 다건 조회가 없어 종목마다 한 번씩 부른다. 응답은 "지금 활성인 항목"만 담고, 없으면 200 + 빈 배열이다
@Service
public class StockWarningService {

	private static final Logger log = LoggerFactory.getLogger(StockWarningService.class);
	private static final long DEFAULT_RETRY_AFTER_MS = 1000;

	private final RestClient restClient;
	private final TokenService tokenService;
	private final LongConsumer sleeper;

	@Autowired
	public StockWarningService(RestClient tossRestClient, TokenService tokenService) {
		this(tossRestClient, tokenService, StockWarningService::sleepMillis);
	}

	// 테스트에서 실제로 기다리지 않도록 대기 방법을 바꿔 끼울 수 있게 둔다
	StockWarningService(RestClient restClient, TokenService tokenService, LongConsumer sleeper) {
		this.restClient = restClient;
		this.tokenService = tokenService;
		this.sleeper = sleeper;
	}

	// 429는 Retry-After만큼 기다려 한 번만 재시도한다. 그래도 실패하거나 다른 오류면 StockWarningLookupException
	public List<StockWarning> getWarnings(String symbol) {
		try {
			return fetch(symbol);
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() != 429) {
				throw new StockWarningLookupException(symbol, "HTTP " + e.getStatusCode().value(), e);
			}
			long waitMs = readRetryAfterMs(e);
			log.warn("[경고 조회] {} 호출 제한(429). {}ms 대기 후 재시도합니다.", symbol, waitMs);
			sleeper.accept(waitMs);
			try {
				return fetch(symbol);
			} catch (RestClientException retryFailure) {
				throw new StockWarningLookupException(symbol, "429 재시도 실패", retryFailure);
			}
		} catch (RestClientException e) {
			throw new StockWarningLookupException(symbol, "요청 실패", e);
		}
	}

	private List<StockWarning> fetch(String symbol) {
		StockWarningsResponse response = restClient.get()
			.uri("/api/v1/stocks/{symbol}/warnings", symbol)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(StockWarningsResponse.class);

		// 본문이 비었을 때 빈 목록으로 돌려주면 "경고 없음"과 구분이 안 되어 위험한 종목이 통과한다. 그래서 실패로 다룬다
		if (response == null || response.result() == null) {
			throw new StockWarningLookupException(symbol, "응답 본문이 비어 있습니다");
		}
		return response.result();
	}

	private static long readRetryAfterMs(RestClientResponseException e) {
		String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
		if (retryAfter == null) {
			return DEFAULT_RETRY_AFTER_MS;
		}
		try {
			return Long.parseLong(retryAfter.trim()) * 1000;
		} catch (NumberFormatException ignored) {
			return DEFAULT_RETRY_AFTER_MS;
		}
	}

	private static void sleepMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
