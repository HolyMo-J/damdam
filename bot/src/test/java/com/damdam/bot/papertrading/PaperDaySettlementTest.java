package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.damdam.bot.papertrading.PaperTestSupport.STRATEGY;
import static com.damdam.bot.papertrading.PaperTestSupport.bar;
import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static com.damdam.bot.papertrading.PaperTestSupport.position;
import static com.damdam.bot.papertrading.PaperTestSupport.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 정산일은 2026-10-01(목)이고, 보유 포지션은 2026-09-29(화)에 진입한 것으로 만든다 (PaperTestSupport.position).
// 슬리피지는 0으로 두어 진입가가 시가와 같다. 가격이 20만원인 종목은 ATR 1000 -> 익절 201000, 손절 감시 199000
class PaperDaySettlementTest {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

	// 20만원 종목의 아무 것도 체결되지 않는 봉 (고가 200500 <= 익절 201000, 저가 199500 > 손절 감시 199000)
	private static Candle quiet200k() {
		return bar(DAY, "200000", "200500", "199500", "200000");
	}

	private static PaperDaySettlement.Result settle(List<PaperPosition> positions, List<PaperTrade> closed,
			List<PendingSignal> pending, Map<String, Candle> bars) {
		return PaperDaySettlement.settle(STRATEGY, DAY, positions, closed, pending, bars, BigDecimal.ZERO);
	}

	private static PaperTrade loss(LocalDate exitDate, String netProfit) {
		return new PaperTrade(STRATEGY, "OLD", 1, exitDate, exitDate, exitDate, bd("10000"), bd("9000"), 1,
			PaperTrade.ExitReason.STOP_LOSS, bd(netProfit), BigDecimal.ZERO, false, false, false, false, BigDecimal.ZERO);
	}

	private static List<String> symbols(List<PaperPosition> positions) {
		return positions.stream().map(PaperPosition::symbol).toList();
	}

	@Test
	void entersPendingSignalsInRankOrderAndSkipsWhatDoesNotFitTheExposureLimit() {
		// 순위 3, 1, 2 순으로 넘겨도 순위 순으로 산다. 20만원 x 2 = 40만원이고 세 번째는 60만원이 되어 건너뛴다
		List<PendingSignal> pending = List.of(signal("C", 3, "1000", "200000"), signal("A", 1, "1000", "200000"),
			signal("B", 2, "1000", "200000"));
		Map<String, Candle> bars = Map.of("A", quiet200k(), "B", quiet200k(), "C", quiet200k());

		PaperDaySettlement.Result r = settle(List.of(), List.of(), pending, bars);

		assertEquals(List.of("A", "B"), symbols(r.positions()));
		assertEquals(1, r.skipped().size());
		assertEquals("C", r.skipped().get(0).signal().symbol());
		assertEquals(SkipReason.EXPOSURE_LIMIT, r.skipped().get(0).reason());
		assertTrue(r.newTrades().isEmpty());
		assertTrue(r.complete());
		// 오늘 진입한 포지션도 오늘 봉으로 이미 한 번 처리됐다
		assertEquals(1, r.positions().get(0).barsProcessed());
	}

	@Test
	void aLaterCheaperSignalCanStillEnterAfterAnExpensiveOneWasSkipped() {
		// 순위 1(20만원) 진입, 순위 2(40만원)는 한도 초과로 건너뛰고, 순위 3(10만원)은 남은 30만원 안이라 들어간다
		List<PendingSignal> pending = List.of(signal("A", 1, "1000", "200000"), signal("B", 2, "1000", "400000"),
			signal("C", 3, "500", "100000"));
		Map<String, Candle> bars = Map.of("A", quiet200k(), "B", bar(DAY, "400000", "400500", "399500", "400000"),
			"C", bar(DAY, "100000", "100200", "99800", "100000"));

		PaperDaySettlement.Result r = settle(List.of(), List.of(), pending, bars);

		assertEquals(List.of("A", "C"), symbols(r.positions()));
		assertEquals(SkipReason.EXPOSURE_LIMIT, r.skipped().get(0).reason());
		assertEquals("B", r.skipped().get(0).signal().symbol());
	}

	@Test
	void aPriceLargerThanTheWholeLimitIsSkippedForItsOwnReason() {
		PaperDaySettlement.Result r = settle(List.of(), List.of(), List.of(signal("BIG", 1, "1000", "600000")),
			Map.of("BIG", bar(DAY, "600000", "600500", "599500", "600000")));

		assertEquals(SkipReason.PRICE_EXCEEDS_LIMIT, r.skipped().get(0).reason());
		assertTrue(r.positions().isEmpty());
	}

	@Test
	void exposureFreedByATodayExitIsNotAvailableToTodaysEntries() {
		// 보유 X(40만원)가 오늘 익절 청산되지만, 진입은 청산보다 먼저라서 Y(20만원)는 40 + 20 = 60만원으로 한도 초과다
		PaperPosition x = position("X", "400000", "1000");
		PaperDaySettlement.Result r = settle(List.of(x), List.of(), List.of(signal("Y", 1, "1000", "200000")),
			Map.of("X", bar(DAY, "400000", "401500", "399500", "401000"), "Y", quiet200k()));

		assertEquals(1, r.newTrades().size());
		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, r.newTrades().get(0).exitReason());
		assertEquals(SkipReason.EXPOSURE_LIMIT, r.skipped().get(0).reason());
		assertTrue(r.positions().isEmpty());
	}

	@Test
	void theFreedExposureIsAvailableTheNextDay() {
		PaperDaySettlement.Result r = settle(List.of(), List.of(), List.of(signal("Y", 1, "1000", "200000")),
			Map.of("Y", quiet200k()));

		assertEquals(List.of("Y"), symbols(r.positions()));
	}

	@Test
	void aSymbolHeldAtTheStartOfTheDayCannotBeReEnteredEvenIfItExitsToday() {
		PaperPosition x = position("X", "200000", "1000");
		PaperDaySettlement.Result r = settle(List.of(x), List.of(), List.of(signal("X", 1, "1000", "200000")),
			Map.of("X", bar(DAY, "200000", "201500", "199500", "201000")));

		assertEquals(SkipReason.ALREADY_HELD, r.skipped().get(0).reason());
		assertEquals(1, r.newTrades().size());
		assertTrue(r.positions().isEmpty());
	}

	@Test
	void weeklyHaltSkipsNewEntriesButKeepsProcessingExits() {
		// 이번 주(9/28~) 화요일에 실현 손실 6만원 > 한도 5만원
		PaperPosition x = position("X", "200000", "1000");
		PaperDaySettlement.Result r = settle(List.of(x), List.of(loss(LocalDate.of(2026, 9, 29), "-60000")),
			List.of(signal("Z", 1, "1000", "200000")), Map.of("X", quiet200k(), "Z", quiet200k()));

		assertEquals(SkipReason.WEEKLY_HALT, r.skipped().get(0).reason());
		assertEquals(List.of("X"), symbols(r.positions()));
		assertEquals(1, r.positions().get(0).barsProcessed());
	}

	@Test
	void anEntryThatIsStoppedOutOnItsOwnEntryDayIsRecordedAsSuch() {
		PaperDaySettlement.Result r = settle(List.of(), List.of(), List.of(signal("W", 1, "100", "10000")),
			Map.of("W", bar(DAY, "10000", "10050", "9890", "9900")));

		assertTrue(r.positions().isEmpty());
		assertTrue(r.skipped().isEmpty());
		assertEquals(1, r.newTrades().size());
		PaperTrade t = r.newTrades().get(0);
		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertTrue(t.exitOnEntryDay());
		assertEquals(DAY, t.entryDate());
		assertEquals(DAY, t.exitDate());
	}

	@Test
	void aZeroVolumePendingSignalIsSkippedWithItsReasonAndAHeldHaltedStockGetsANote() {
		PaperPosition held = position("H", "200000", "1000");
		PaperDaySettlement.Result r = settle(List.of(held), List.of(), List.of(signal("Z", 1, "1000", "200000")),
			Map.of("H", bar(DAY, "200000", "200000", "200000", "200000", "0"),
				"Z", bar(DAY, "200000", "200000", "200000", "200000", "0")));

		assertEquals(SkipReason.ZERO_VOLUME, r.skipped().get(0).reason());
		assertEquals(List.of(new PaperDaySettlement.HoldNote("H", PaperExit.Note.HALTED)), r.notes());
		assertEquals(List.of("H"), symbols(r.positions()));
	}

	@Test
	void aMissingBarLeavesThatSymbolUnsettledAndTheDayIncomplete() {
		PaperPosition noBar = position("NOBAR", "200000", "1000");
		PaperPosition withBar = position("OK", "200000", "1000");
		PaperDaySettlement.Result r = settle(List.of(noBar, withBar), List.of(),
			List.of(signal("PEND", 1, "1000", "200000")), Map.of("OK", quiet200k()));

		assertFalse(r.complete());
		assertEquals(List.of("NOBAR", "PEND"), r.unsettledSymbols());
		// 봉이 없는 보유 포지션은 그대로, 대기 신호는 진입도 건너뜀 기록도 하지 않는다
		assertEquals(noBar, r.positions().get(0));
		assertEquals(0, r.positions().get(0).barsProcessed());
		assertEquals(1, r.positions().get(1).barsProcessed());
		assertTrue(r.skipped().isEmpty());
	}

	@Test
	void settlingTheSameInputTwiceGivesTheSameResult() {
		List<PendingSignal> pending = List.of(signal("A", 1, "1000", "200000"), signal("B", 2, "1000", "200000"));
		Map<String, Candle> bars = Map.of("A", quiet200k(), "B", quiet200k());

		assertEquals(settle(List.of(), List.of(), pending, bars), settle(List.of(), List.of(), pending, bars));
	}

	@Test
	void slippageChangesTheEntryPriceOfTheSameFlow() {
		List<PendingSignal> pending = List.of(signal("A", 1, "1000", "200000"));
		Map<String, Candle> bars = Map.of("A", quiet200k());

		PaperDaySettlement.Result zero = PaperDaySettlement.settle(STRATEGY, DAY, List.of(), List.of(), pending, bars, BigDecimal.ZERO);
		PaperDaySettlement.Result some = PaperDaySettlement.settle(STRATEGY, DAY, List.of(), List.of(), pending, bars, bd("0.002"));

		assertEquals(0, bd("200000").compareTo(zero.positions().get(0).entryPrice()));
		// 200000 x 1.002 = 200400 (고가 200500 이하라 제한되지 않음)
		assertEquals(0, bd("200400").compareTo(some.positions().get(0).entryPrice()));
	}

	@Test
	void rejectsABarWhoseDateIsNotTheSettlementDate() {
		Candle yesterday = bar(DAY.minusDays(1), "200000", "200500", "199500", "200000");

		assertThrows(IllegalArgumentException.class, () -> settle(List.of(), List.of(),
			List.of(signal("A", 1, "1000", "200000")), Map.of("A", yesterday)));
	}

	@Test
	void unsettledSymbolsAreSortedSoRerunsPrintTheSameOutput() {
		List<PaperPosition> held = List.of(position("C", "200000", "1000"), position("A", "200000", "1000"),
			position("B", "200000", "1000"));

		PaperDaySettlement.Result r = settle(held, List.of(), List.of(), Map.of());

		assertEquals(List.of("A", "B", "C"), r.unsettledSymbols());
	}

	@Test
	void feedingTheSavedResultBackForTheSameDayIsRejectedInsteadOfDoubleProcessing() {
		PaperPosition x = position("X", "200000", "1000");
		Map<String, Candle> bars = Map.of("X", quiet200k());
		PaperDaySettlement.Result first = settle(List.of(x), List.of(), List.of(), bars);

		assertThrows(IllegalArgumentException.class, () -> settle(first.positions(), List.of(), List.of(), bars));
	}

	@Test
	void rejectsClosedTradesFromAnotherStrategy() {
		PaperTrade other = new PaperTrade("A", "OLD", 1, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29),
			LocalDate.of(2026, 9, 29), bd("10000"), bd("9000"), 1, PaperTrade.ExitReason.STOP_LOSS, bd("-1"),
			BigDecimal.ZERO, false, false, false, false, BigDecimal.ZERO);

		assertThrows(IllegalArgumentException.class, () -> settle(List.of(), List.of(other), List.of(), Map.of()));
	}

	@Test
	void rejectsMixedStrategiesAndDuplicateSymbols() {
		PaperPosition p = position("A", "200000", "1000");
		PendingSignal otherStrategy = new PendingSignal("A", "Z", 1, PaperTestSupport.SIGNAL_DATE, bd("1000"), bd("200000"));

		assertThrows(IllegalArgumentException.class,
			() -> settle(List.of(), List.of(), List.of(otherStrategy), Map.of("Z", quiet200k())));
		assertThrows(IllegalArgumentException.class,
			() -> settle(List.of(p, p), List.of(), List.of(), Map.of("A", quiet200k())));
	}
}
