package com.damdam.bot.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 오늘 날짜 봉(장중에는 아직 확정되지 않은 진행 중인 캔들일 수 있음)을 ATR 계산에서 제외하는지 확인한다
class AtrServiceTest {

	private static final String SYMBOL = "005930";
	private static final int PERIOD = 14;
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);
	// 이 시각(UTC 05:00)은 한국(+09:00)은 이미 09-29(다음날)이지만, 시스템 기본 시간대와 무관하게
	// 테스트가 항상 같은 결과를 내도록 실제 시각(Instant)만 고정하고 시간대 판정은 각 캔들 자신의 offset으로 한다
	private static final Instant NOW = Instant.parse("2026-09-28T05:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static Candle candle(ZoneOffset offset, int daysAgo, String high, String low, String close) {
		LocalDate today = OffsetDateTime.ofInstant(NOW, offset).toLocalDate();
		OffsetDateTime timestamp = today.minusDays(daysAgo).atStartOfDay().atOffset(offset);
		return new Candle(timestamp.toString(), close, high, low, close, "1000", "KRW");
	}

	private static Candle candle(int daysAgo, String high, String low, String close) {
		return candle(KST, daysAgo, high, low, close);
	}

	// daysAgo가 count부터 1까지 내려가는 순서(최신순, 오늘에 가까운 순)로 만든다
	private static List<Candle> settledCandles(ZoneOffset offset, int count) {
		List<Candle> candles = new ArrayList<>();
		for (int daysAgo = 1; daysAgo <= count; daysAgo++) {
			candles.add(candle(offset, daysAgo, "110", "90", "100"));
		}
		return candles;
	}

	private static List<Candle> settledCandles(int count) {
		return settledCandles(KST, count);
	}

	@Test
	void excludesTodayCandleFromCalculation() {
		List<Candle> withToday = new ArrayList<>();
		withToday.add(candle(0, "999", "1", "500")); // 오늘 진행 중인 봉. 극단적인 고가/저가라 섞이면 결과가 확 달라진다
		withToday.addAll(settledCandles(PERIOD + 1));
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(withToday);
		AtrService atrService = new AtrService(marketDataService, CLOCK);

		BigDecimal atr = atrService.getAtr14(SYMBOL);

		BigDecimal expected = AverageTrueRange.calculate(settledCandles(PERIOD + 1), PERIOD);
		assertEquals(expected, atr);
	}

	@Test
	void worksWithoutTodayCandle() {
		List<Candle> candles = settledCandles(PERIOD + 2);
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
		AtrService atrService = new AtrService(marketDataService, CLOCK);

		BigDecimal atr = atrService.getAtr14(SYMBOL);

		BigDecimal expected = AverageTrueRange.calculate(candles.subList(0, PERIOD + 1), PERIOD);
		assertEquals(expected, atr);
	}

	@Test
	void throwsWhenNotEnoughCandlesAfterExcludingToday() {
		List<Candle> withToday = new ArrayList<>();
		withToday.add(candle(0, "999", "1", "500"));
		withToday.addAll(settledCandles(PERIOD)); // 오늘 봉을 빼면 14개뿐이라 (15개 필요) 부족하다
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(withToday);
		AtrService atrService = new AtrService(marketDataService, CLOCK);

		assertThrows(IllegalStateException.class, () -> atrService.getAtr14(SYMBOL));
	}

	// 이 시각 기준 한국은 이미 다음날(09-29)이지만 미국 동부(-04:00)는 아직 오늘(09-28)이다. 시스템 시간대가 아니라
	// 캔들 자신의 시간대로 "오늘"을 판정해야, 해외 종목의 진행 중인 봉을 한국 날짜 기준으로 잘못 포함시키지 않는다
	@Test
	void excludesTodayCandleForAForeignSymbolUsingItsOwnLocalOffset() {
		ZoneOffset usEastern = ZoneOffset.ofHours(-4);
		List<Candle> withToday = new ArrayList<>();
		withToday.add(candle(usEastern, 0, "999", "1", "500"));
		withToday.addAll(settledCandles(usEastern, PERIOD + 1));
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(withToday);
		AtrService atrService = new AtrService(marketDataService, CLOCK);

		BigDecimal atr = atrService.getAtr14("AAPL");

		BigDecimal expected = AverageTrueRange.calculate(settledCandles(usEastern, PERIOD + 1), PERIOD);
		assertEquals(expected, atr);
	}
}
