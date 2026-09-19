package com.damdam.bot.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

	private static Candle candle(int daysAgo, String high, String low, String close) {
		OffsetDateTime timestamp = LocalDate.now().minusDays(daysAgo).atStartOfDay().atOffset(ZoneOffset.ofHours(9));
		return new Candle(timestamp.toString(), close, high, low, close, "1000", "KRW");
	}

	// daysAgo가 count부터 1까지 내려가는 순서(최신순, 오늘에 가까운 순)로 만든다
	private static List<Candle> settledCandles(int count) {
		List<Candle> candles = new ArrayList<>();
		for (int daysAgo = 1; daysAgo <= count; daysAgo++) {
			candles.add(candle(daysAgo, "110", "90", "100"));
		}
		return candles;
	}

	@Test
	void excludesTodayCandleFromCalculation() {
		List<Candle> withToday = new ArrayList<>();
		withToday.add(candle(0, "999", "1", "500")); // 오늘 진행 중인 봉. 극단적인 고가/저가라 섞이면 결과가 확 달라진다
		withToday.addAll(settledCandles(PERIOD + 1));
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(withToday);
		AtrService atrService = new AtrService(marketDataService);

		BigDecimal atr = atrService.getAtr14(SYMBOL);

		BigDecimal expected = AverageTrueRange.calculate(settledCandles(PERIOD + 1), PERIOD);
		assertEquals(expected, atr);
	}

	@Test
	void worksWithoutTodayCandle() {
		List<Candle> candles = settledCandles(PERIOD + 2);
		MarketDataService marketDataService = mock(MarketDataService.class);
		when(marketDataService.getDailyCandles(anyString(), anyInt())).thenReturn(candles);
		AtrService atrService = new AtrService(marketDataService);

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
		AtrService atrService = new AtrService(marketDataService);

		assertThrows(IllegalStateException.class, () -> atrService.getAtr14(SYMBOL));
	}
}
