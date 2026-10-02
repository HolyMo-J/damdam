package com.damdam.bot.papertrader;

import com.damdam.bot.market.Candle;
import com.damdam.bot.stocks.StockLookupException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.candle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementBarsLoaderTest {

	private static final LocalDate THU = LocalDate.of(2026, 10, 1);
	private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
	private static final LocalDate MON = LocalDate.of(2026, 10, 5);

	private static boolean weekday(LocalDate date) {
		return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	private static Candle bar(LocalDate date) {
		return candle(date, "100", "110", "90", "105", "1000");
	}

	@Test
	void groupsEachSymbolsCandlesByTradingDayAndSkipsWeekendsAndOutOfRangeBars() {
		Map<String, List<Candle>> source = Map.of(
			"AAA", List.of(bar(MON), bar(FRI), bar(THU), bar(THU.minusDays(1))),   // 맨 뒤는 범위 밖
			"BBB", List.of(bar(MON), bar(FRI)));
		SettlementBarsLoader loader = new SettlementBarsLoader(source::get, SettlementBarsLoaderTest::weekday, 0);

		SettlementBarsLoader.Loaded loaded = loader.load(FRI, MON, List.of("AAA", "BBB"));

		assertEquals(List.of(FRI, MON), List.copyOf(loaded.barsByDate().keySet()));   // 토요일, 일요일은 없다
		assertEquals(java.util.Set.of("AAA", "BBB"), loaded.barsByDate().get(FRI).keySet());
		assertEquals(java.util.Set.of("AAA", "BBB"), loaded.barsByDate().get(MON).keySet());
		assertEquals(List.of(), loaded.failedSymbols());
	}

	@Test
	void aSymbolWhoseFetchFailsIsListedAndAbsentFromEveryDay() {
		Function<String, List<Candle>> source = symbol -> {
			if (symbol.equals("BAD")) {
				throw new StockLookupException(symbol, "일봉 조회", "HTTP 500");
			}
			return List.of(bar(FRI));
		};
		SettlementBarsLoader loader = new SettlementBarsLoader(source, SettlementBarsLoaderTest::weekday, 0);

		SettlementBarsLoader.Loaded loaded = loader.load(FRI, FRI, List.of("AAA", "BAD"));

		assertEquals(List.of("BAD"), loaded.failedSymbols());
		assertEquals(java.util.Set.of("AAA"), loaded.barsByDate().get(FRI).keySet());
	}

	@Test
	void everyTradingDayHasAMapEvenWhenNoSymbolHasABarSoTheLedgerCanSeeASystemicMiss() {
		SettlementBarsLoader loader = new SettlementBarsLoader(symbol -> List.of(), SettlementBarsLoaderTest::weekday, 0);

		SettlementBarsLoader.Loaded loaded = loader.load(FRI, FRI, List.of("AAA"));

		assertEquals(List.of(FRI), List.copyOf(loaded.barsByDate().keySet()));
		assertTrue(loaded.barsByDate().get(FRI).isEmpty());
	}

	@Test
	void nothingIsFetchedWhenThereIsNoTradingDayToSettle() {
		AtomicInteger calls = new AtomicInteger();
		SettlementBarsLoader loader = new SettlementBarsLoader(symbol -> {
			calls.incrementAndGet();
			return List.of();
		}, SettlementBarsLoaderTest::weekday, 0);

		// 이미 신호일까지 정산한 뒤의 재실행: 시작일이 신호일 다음 날이다
		SettlementBarsLoader.Loaded afterSettled = loader.load(FRI.plusDays(1), FRI, List.of("AAA"));
		// 토요일부터 일요일까지만 있는 구간
		SettlementBarsLoader.Loaded weekendOnly = loader.load(FRI.plusDays(1), FRI.plusDays(2), List.of("AAA"));

		assertTrue(afterSettled.barsByDate().isEmpty());
		assertTrue(weekendOnly.barsByDate().isEmpty());
		assertEquals(0, calls.get());
	}

	@Test
	void tooManyMissedTradingDaysFailsInsteadOfSettlingPartially() {
		SettlementBarsLoader loader = new SettlementBarsLoader(symbol -> List.of(), date -> true, 0);

		LocalDate from = FRI.minusDays(SettlementBarsLoader.MAX_CATCH_UP_TRADING_DAYS);   // 신호일까지 91일(양 끝 포함)

		assertThrows(IllegalStateException.class, () -> loader.load(from, FRI, List.of("AAA")));
		// 한도 안(90일)이면 통과한다
		assertEquals(SettlementBarsLoader.MAX_CATCH_UP_TRADING_DAYS,
			loader.load(from.plusDays(1), FRI, new ArrayList<>()).barsByDate().size());
	}

	@Test
	void aBarWithAnUnreadableDateIsSkippedNotFatal() {
		Candle broken = new Candle("어제", "100", "110", "90", "105", "1000", "KRW");
		SettlementBarsLoader loader = new SettlementBarsLoader(symbol -> List.of(broken, bar(FRI)), SettlementBarsLoaderTest::weekday, 0);

		SettlementBarsLoader.Loaded loaded = loader.load(FRI, FRI, List.of("AAA"));

		assertEquals(java.util.Set.of("AAA"), loaded.barsByDate().get(FRI).keySet());
	}
}
