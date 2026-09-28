package com.damdam.bot.stocks;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.CandlePageResponse;
import com.damdam.bot.market.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.LongConsumer;

// 가상매매 신호 판정에 넣을 입력(일봉, 기관 매매동향)을 종목 하나 단위로 조회한다. 둘 다 조회 전용이다.
// 기존 캔들 조회(MarketDataService)는 ATR 계산 등 다른 곳도 쓰므로 고치지 않고, 여기서 429 재시도와 예외 변환만 씌운다.
// 예외 변환이 papertrading이 아니라 여기 있는 이유는 papertrading 소스가 RestClient 계열 이름을 쓰지 못하기 때문이다
@Service
public class SignalInputService {

	// 전략 B는 78봉이 필요하다(IchimokuCloudBreakout.MIN_CANDLES). 한 번에 최대 200봉이라 100봉이면 한 번 호출로 충분하다
	public static final int CANDLE_COUNT = 100;
	// 전략 A는 최근 3거래일 기록이 필요하다. 3일치에 여유를 둔 값이다
	public static final int FLOW_COUNT = 10;

	private final MarketDataService marketDataService;
	private final InvestorTradingService investorTradingService;
	private final LongConsumer sleeper;

	@Autowired
	public SignalInputService(MarketDataService marketDataService, InvestorTradingService investorTradingService) {
		this(marketDataService, investorTradingService, RateLimitRetry::sleepMillis);
	}

	SignalInputService(MarketDataService marketDataService, InvestorTradingService investorTradingService, LongConsumer sleeper) {
		this.marketDataService = marketDataService;
		this.investorTradingService = investorTradingService;
		this.sleeper = sleeper;
	}

	// 수정주가 기준 최신순 일봉. 응답이 비었거나 봉에 값이 빠져 있으면 실패로 다룬다 (거래대금 상위 종목의 일봉이 0개인 것은 조회 문제이고,
	// 빈 목록을 돌려주면 호출부가 "봉이 모자란 신규 상장"으로 오해해 조용히 건너뛴다. 값이 빠진 봉은 신호 계산에서 NPE를 내 스캔 전체를 멈춘다).
	// getDailyCandles 대신 페이지 조회를 쓰는 이유: 본문이 {} 이면 result가 null이라 getDailyCandles는 NPE로 끝나는데,
	// ATR 계산 등 다른 곳도 쓰는 MarketDataService를 고치지 않고 여기서 null을 조회 실패로 바꾸기 위해서다
	public List<Candle> getDailyCandles(String symbol) {
		CandlePageResponse page = RateLimitRetry.call(symbol + " 일봉 조회", () -> marketDataService.getDailyCandlesPage(symbol, CANDLE_COUNT, null), sleeper,
			(message, cause) -> new StockLookupException(symbol, "일봉 조회", message, cause));
		if (page == null || page.candles() == null || page.candles().isEmpty()) {
			throw new StockLookupException(symbol, "일봉 조회", "응답에 봉이 없습니다");
		}
		for (Candle candle : page.candles()) {
			if (candle == null || candle.timestamp() == null || candle.highPrice() == null || candle.lowPrice() == null
				|| candle.closePrice() == null || candle.volume() == null) {
				throw new StockLookupException(symbol, "일봉 조회", "봉에 시각, 고가, 저가, 종가, 거래량 중 빠진 값이 있습니다");
			}
		}
		return page.candles();
	}

	public List<InvestorTradingRecord> getInstitutionFlows(String symbol) {
		return investorTradingService.getRecentRecords(symbol, FLOW_COUNT);
	}
}
