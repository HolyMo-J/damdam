package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 2026-09-28은 월요일이다
class WeeklyLossLimitTest {

	private static final LocalDate MON = LocalDate.of(2026, 9, 28);
	private static final LocalDate TUE = LocalDate.of(2026, 9, 29);
	private static final LocalDate THU = LocalDate.of(2026, 10, 1);
	private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
	private static final LocalDate NEXT_MON = LocalDate.of(2026, 10, 5);

	private static PaperTrade trade(LocalDate exitDate, String netProfit) {
		return new PaperTrade("B", "X", 1, MON, MON, exitDate, bd("10000"), bd("9000"), 1,
			PaperTrade.ExitReason.STOP_LOSS, bd(netProfit), BigDecimal.ZERO, false, false, false, false, BigDecimal.ZERO);
	}

	@Test
	void weekStartIsTheMonday() {
		assertEquals(MON, WeeklyLossLimit.weekStart(MON));
		assertEquals(MON, WeeklyLossLimit.weekStart(FRI));
		assertEquals(MON, WeeklyLossLimit.weekStart(LocalDate.of(2026, 10, 4)));   // 일요일
		assertEquals(NEXT_MON, WeeklyLossLimit.weekStart(NEXT_MON));
	}

	@Test
	void limitIsTenPercentOfTheVirtualCapital() {
		assertEquals(0, bd("50000").compareTo(WeeklyLossLimit.LIMIT));
	}

	@Test
	void haltsWhenTheWeeksRealizedLossExceedsTheLimit() {
		assertTrue(WeeklyLossLimit.isHalted(THU, List.of(trade(TUE, "-50000.01"))));
	}

	@Test
	void doesNotHaltWhenTheLossEqualsTheLimit() {
		assertFalse(WeeklyLossLimit.isHalted(THU, List.of(trade(TUE, "-50000"))));
	}

	@Test
	void sumsLossesAcrossTradesInTheSameWeek() {
		assertTrue(WeeklyLossLimit.isHalted(THU, List.of(trade(MON, "-30000"), trade(TUE, "-25000"))));
	}

	@Test
	void profitsOffsetLossesInTheNetRealizedTotal() {
		assertFalse(WeeklyLossLimit.isHalted(THU, List.of(trade(MON, "-60000"), trade(TUE, "20000"))));
	}

	@Test
	void ignoresLossesFromThePreviousWeek() {
		assertFalse(WeeklyLossLimit.isHalted(THU, List.of(trade(LocalDate.of(2026, 9, 25), "-90000"))));
	}

	@Test
	void theHaltEndsWhenANewWeekStarts() {
		assertTrue(WeeklyLossLimit.isHalted(FRI, List.of(trade(THU, "-90000"))));
		assertFalse(WeeklyLossLimit.isHalted(NEXT_MON, List.of(trade(FRI, "-90000"))));
	}

	@Test
	void lossesRealizedOnTheEntryDayItselfAreNotCounted() {
		// 진입은 그날 청산보다 먼저 처리한다
		assertFalse(WeeklyLossLimit.isHalted(THU, List.of(trade(THU, "-90000"))));
	}
}
