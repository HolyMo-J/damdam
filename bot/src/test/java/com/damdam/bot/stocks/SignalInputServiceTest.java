package com.damdam.bot.stocks;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.CandlePageResponse;
import com.damdam.bot.market.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignalInputServiceTest {

	private static final Candle CANDLE = new Candle("2026-09-28T00:00+09:00", "100", "110", "90", "105", "1000", "KRW");

	private MarketDataService marketDataService;
	private InvestorTradingService investorTradingService;
	private SignalInputService service;
	private final List<Long> sleeps = new ArrayList<>();

	@BeforeEach
	void setUp() {
		marketDataService = mock(MarketDataService.class);
		investorTradingService = mock(InvestorTradingService.class);
		service = new SignalInputService(marketDataService, investorTradingService, sleeps::add);
	}

	private static HttpClientErrorException tooManyRequests(String retryAfter) {
		HttpHeaders headers = new HttpHeaders();
		if (retryAfter != null) {
			headers.set("Retry-After", retryAfter);
		}
		return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers, new byte[0], null);
	}

	@Test
	void requestsHundredDailyCandlesAndReturnsThemAsIs() {
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenReturn(page(CANDLE));
		assertEquals(List.of(CANDLE), service.getDailyCandles("A"));
	}

	@Test
	void retriesOnceOn429UsingRetryAfter() {
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenThrow(tooManyRequests("2")).thenReturn(page(CANDLE));

		assertEquals(List.of(CANDLE), service.getDailyCandles("A"));
		assertEquals(List.of(2000L), sleeps);
		verify(marketDataService, times(2)).getDailyCandlesPage("A", 100, null);
	}

	@Test
	void secondRateLimitFailsAsLookupException() {
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenThrow(tooManyRequests(null));

		assertThrows(StockLookupException.class, () -> service.getDailyCandles("A"));
		assertEquals(List.of(1000L), sleeps);
		verify(marketDataService, times(2)).getDailyCandlesPage("A", 100, null);
	}

	@Test
	void otherHttpErrorsAreWrappedWithoutRetry() {
		when(marketDataService.getDailyCandlesPage("A", 100, null))
			.thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", new HttpHeaders(), new byte[0], null));

		assertThrows(StockLookupException.class, () -> service.getDailyCandles("A"));
		assertEquals(List.of(), sleeps);
		verify(marketDataService, times(1)).getDailyCandlesPage("A", 100, null);
	}

	private static CandlePageResponse page(Candle... candles) {
		return new CandlePageResponse(List.of(candles), null);
	}

	@Test
	void emptyCandleListIsAFailureNotAnInsufficientCandlesHint() {
		// 응답 본문이 비면 빈 목록이 온다. 그것을 "봉이 모자란 신규 상장"으로 오해하지 않게 실패로 올린다
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenReturn(page());
		assertThrows(StockLookupException.class, () -> service.getDailyCandles("A"));
	}

	@Test
	void nullPageOrNullCandleListIsAFailureNotANullPointerException() {
		// 본문이 {} 이면 MarketDataService는 result가 null이라 null을 돌려준다 (getDailyCandles는 이 경우 NPE로 끝난다)
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenReturn(null);
		assertThrows(StockLookupException.class, () -> service.getDailyCandles("A"));

		when(marketDataService.getDailyCandlesPage("B", 100, null)).thenReturn(new CandlePageResponse(null, null));
		assertThrows(StockLookupException.class, () -> service.getDailyCandles("B"));
	}

	@Test
	void candleWithMissingValuesFailsTheLookupInsteadOfCrashingTheScan() {
		for (Candle broken : List.of(
			new Candle(null, "1", "1", "1", "1", "1", "KRW"),
			new Candle("2026-09-28T00:00+09:00", "1", null, "1", "1", "1", "KRW"),
			new Candle("2026-09-28T00:00+09:00", "1", "1", null, "1", "1", "KRW"),
			new Candle("2026-09-28T00:00+09:00", "1", "1", "1", null, "1", "KRW"),
			new Candle("2026-09-28T00:00+09:00", "1", "1", "1", "1", null, "KRW"))) {
			when(marketDataService.getDailyCandlesPage("A", 100, null)).thenReturn(page(CANDLE, broken));
			assertThrows(StockLookupException.class, () -> service.getDailyCandles("A"), broken.toString());
		}
	}

	@Test
	void retryAfterIsClampedToASaneRange() {
		// 음수면 Thread.sleep이 예외를 던지고, 아주 크면 스캔이 오래 멈춘다. 음수는 기본 1초, 큰 값은 60초로 자른다
		when(marketDataService.getDailyCandlesPage("A", 100, null)).thenThrow(tooManyRequests("-5")).thenReturn(page(CANDLE));
		service.getDailyCandles("A");
		when(marketDataService.getDailyCandlesPage("B", 100, null)).thenThrow(tooManyRequests("99999")).thenReturn(page(CANDLE));
		service.getDailyCandles("B");
		when(marketDataService.getDailyCandlesPage("C", 100, null)).thenThrow(tooManyRequests("abc")).thenReturn(page(CANDLE));
		service.getDailyCandles("C");

		assertEquals(List.of(1000L, 60000L, 1000L), sleeps);
	}

	@Test
	void requestsTenInstitutionFlowRecords() {
		List<InvestorTradingRecord> records = List.of(new InvestorTradingRecord(LocalDate.parse("2026-09-28"), new BigDecimal("5"), null));
		when(investorTradingService.getRecentRecords("A", 10)).thenReturn(records);
		assertEquals(records, service.getInstitutionFlows("A"));
	}
}
