package com.damdam.bot.stocks;

import com.damdam.bot.token.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.LongConsumer;

// 매수 유의사항(GET /api/v1/stocks/{symbol}/warnings) 조회. 조회 전용이고 STOCK 그룹(초당 5회)이다.
// 다건 조회가 없어 종목마다 한 번씩 부른다. 응답은 "지금 활성인 항목"만 담고, 없으면 200 + 빈 배열이다
@Service
public class StockWarningService {

	private final RestClient restClient;
	private final TokenService tokenService;
	private final LongConsumer sleeper;

	@Autowired
	public StockWarningService(RestClient tossRestClient, TokenService tokenService) {
		this(tossRestClient, tokenService, RateLimitRetry::sleepMillis);
	}

	// 테스트에서 실제로 기다리지 않도록 대기 방법을 바꿔 끼울 수 있게 둔다
	StockWarningService(RestClient restClient, TokenService tokenService, LongConsumer sleeper) {
		this.restClient = restClient;
		this.tokenService = tokenService;
		this.sleeper = sleeper;
	}

	// 429는 Retry-After만큼 기다려 한 번만 재시도한다. 그래도 실패하거나 다른 오류면 StockWarningLookupException
	public List<StockWarning> getWarnings(String symbol) {
		return RateLimitRetry.call(symbol + " 경고 조회", () -> fetch(symbol), sleeper,
			(message, cause) -> new StockWarningLookupException(symbol, message, cause));
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
}
