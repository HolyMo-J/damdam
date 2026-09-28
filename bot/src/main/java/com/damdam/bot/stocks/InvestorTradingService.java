package com.damdam.bot.stocks;

import com.damdam.bot.token.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.LongConsumer;

// 투자자별 매매동향(GET /api/v1/stocks/{symbol}/investor-trading) 조회. 조회 전용이고 STOCK_TRADING_TREND 그룹(초당 10회)이다.
// 국내 종목만 지원한다. 당일 기록은 장중 잠정치이고 확정치는 그날 저녁에 반영된다(명세). 이 서비스는 값을 그대로 돌려줄 뿐
// 확정 여부는 판단하지 못하므로, 언제 부를지는 호출부 책임이다
@Service
public class InvestorTradingService {

	// 명세상 count 최대값
	static final int MAX_COUNT = 100;

	private final RestClient restClient;
	private final TokenService tokenService;
	private final LongConsumer sleeper;

	@Autowired
	public InvestorTradingService(RestClient tossRestClient, TokenService tokenService) {
		this(tossRestClient, tokenService, RateLimitRetry::sleepMillis);
	}

	InvestorTradingService(RestClient restClient, TokenService tokenService, LongConsumer sleeper) {
		this.restClient = restClient;
		this.tokenService = tokenService;
		this.sleeper = sleeper;
	}

	// 최신순 기록 count건. 429는 Retry-After 뒤 1회 재시도하고, 실패하면 StockLookupException.
	// 기록이 없으면 빈 목록이다. 본문이 비었거나 기록의 날짜와 기관 순매수가 빠진 경우는 빈 목록이 아니라 실패로 다룬다
	public List<InvestorTradingRecord> getRecentRecords(String symbol, int count) {
		if (count < 1 || count > MAX_COUNT) {
			throw new IllegalArgumentException("count는 1 이상 %d 이하여야 합니다: %d".formatted(MAX_COUNT, count));
		}
		return RateLimitRetry.call(symbol + " 매매동향 조회", () -> fetch(symbol, count), sleeper,
			(message, cause) -> new StockLookupException(symbol, "매매동향 조회", message, cause));
	}

	private List<InvestorTradingRecord> fetch(String symbol, int count) {
		InvestorTradingResponse response = restClient.get()
			.uri("/api/v1/stocks/{symbol}/investor-trading?count={count}", symbol, count)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(InvestorTradingResponse.class);

		// 본문이 비었을 때 빈 목록으로 돌려주면 "기록이 없는 종목"과 구분이 안 된다
		if (response == null || response.result() == null || response.result().records() == null) {
			throw new StockLookupException(symbol, "매매동향 조회", "응답 본문이 비어 있습니다");
		}
		return response.result().records().stream().map(row -> toRecord(symbol, row)).toList();
	}

	private static InvestorTradingRecord toRecord(String symbol, InvestorTradingResponse.Row row) {
		if (row == null || row.date() == null || row.institution() == null || row.institution().netBuyVolume() == null) {
			throw new StockLookupException(symbol, "매매동향 조회", "기록에 날짜나 기관 순매수 거래량이 없습니다");
		}
		return new InvestorTradingRecord(row.date(), row.institution().netBuyVolume(), row.updatedAt());
	}
}
