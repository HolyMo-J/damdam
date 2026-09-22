package com.damdam.bot.market;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 국내 증시 개장일 조회 (GET /api/v1/market-calendar/KR). 과거/오늘 날짜의 개장 여부는 다시 바뀌지 않으므로
// 프로세스 생존 기간 동안 영구 캐시한다 (docs/review-tasks.md 9번, TradingDayCalculator가 국내 종목에만 사용)
@Service
public class MarketCalendarService {

	private final RestClient restClient;
	private final TokenService tokenService;
	private final Map<LocalDate, Boolean> cache = new ConcurrentHashMap<>();

	public MarketCalendarService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	public boolean isTradingDay(LocalDate date) {
		Boolean cached = cache.get(date);
		if (cached != null) {
			return cached;
		}
		boolean tradingDay = fetch(date).today().isTradingDay();
		cache.put(date, tradingDay);
		return tradingDay;
	}

	private MarketCalendarResult fetch(LocalDate date) {
		var uri = UriComponentsBuilder.fromPath("/api/v1/market-calendar/KR")
			.queryParam("date", date)
			.build().toUri();
		MarketCalendarResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(MarketCalendarResponse.class);
		if (response == null || response.result() == null) {
			throw new IllegalStateException("market-calendar/KR 응답이 비어 있습니다 (date=" + date + ")");
		}
		return response.result();
	}
}
