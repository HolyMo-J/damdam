package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.damdam.bot.papertrading.PaperTestSupport.ENTRY_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.atBar;
import static com.damdam.bot.papertrading.PaperTestSupport.bar;
import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static com.damdam.bot.papertrading.PaperTestSupport.position;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 기준 포지션: 시가 10000에 슬리피지 0으로 진입, ATR 100 -> 익절 10100, 손절 감시 9900, 손절 주문 9899.
// 청산 판정의 슬리피지는 0.002 (시간 청산 매도에만 쓰인다)
class PaperExitTest {

	private static final BigDecimal SLIPPAGE = bd("0.002");
	private static final LocalDate DAY = ENTRY_DATE.plusDays(1);

	private static PaperPosition base() {
		return position("005930", "10000", "100");
	}

	private static PaperExit.Result evaluate(PaperPosition p, Candle bar) {
		return PaperExit.evaluate(p, DailyCandles.dateOf(bar), bar, SLIPPAGE);
	}

	private static PaperTrade exited(PaperExit.Result result) {
		return ((PaperExit.Exited) result).trade();
	}

	private static PaperExit.Holding holding(PaperExit.Result result) {
		return (PaperExit.Holding) result;
	}

	@Test
	void takeProfitDoesNotFillWhenThePriceOnlyTouchesIt() {
		PaperExit.Holding h = holding(evaluate(base(), bar(DAY, "10000", "10100", "9950", "10050")));

		assertEquals(PaperExit.Note.NONE, h.note());
		assertEquals(1, h.position().barsProcessed());
	}

	@Test
	void takeProfitFillsAtTheTakeProfitPriceWhenTheHighExceedsIt() {
		PaperTrade t = exited(evaluate(base(), bar(DAY, "10000", "10101", "9950", "10050")));

		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, t.exitReason());
		assertEquals(0, bd("10100").compareTo(t.exitPrice()));
		assertFalse(t.gapExit());
		assertEquals(DAY, t.exitDate());
		assertEquals(0, PaperCosts.netProfit(bd("10000"), bd("10100"), 1).compareTo(t.netProfit()));
		assertEquals(0, PaperCosts.netReturn(bd("10000"), bd("10100")).compareTo(t.netReturn()));
	}

	@Test
	void gapUpOverTheTakeProfitStillFillsAtTheTakeProfitPriceAndIsFlagged() {
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "10200", "10300", "10150", "10250")));

		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, t.exitReason());
		assertEquals(0, bd("10100").compareTo(t.exitPrice()));
		assertTrue(t.gapExit());
	}

	@Test
	void stopLossFillsAtTheStopOrderPriceNotTheTrigger() {
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "10000", "10050", "9900", "9950")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertEquals(0, bd("9899").compareTo(t.exitPrice()));
		assertFalse(t.gapExit());
		assertFalse(t.exitOnEntryDay());
	}

	@Test
	void lowJustAboveTheStopTriggerKeepsHolding() {
		PaperExit.Holding h = holding(evaluate(base(), bar(DAY, "10000", "10050", "9901", "9950")));

		assertEquals(PaperExit.Note.NONE, h.note());
	}

	@Test
	void stopLossWinsWhenBothPricesAreCrossedOnTheSameDay() {
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "10000", "10200", "9800", "10000")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertEquals(0, bd("9899").compareTo(t.exitPrice()));
	}

	@Test
	void gapDownThatRecoversToTheStopOrderPriceFillsAtTheOrderPriceAndIsFlagged() {
		// 시가 9850 < 주문가 9899, 그날 고가 9900 >= 9899 -> 주문가에 체결
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "9850", "9900", "9800", "9880")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertEquals(0, bd("9899").compareTo(t.exitPrice()));
		assertTrue(t.gapExit());
	}

	@Test
	void gapDownThatNeverReachesTheStopOrderPriceIsASellFailure() {
		PaperExit.Holding h = holding(evaluate(atBar(base(), 1), bar(DAY, "9800", "9850", "9700", "9750")));

		assertEquals(PaperExit.Note.SELL_FAILED, h.note());
		assertTrue(h.position().sellFailed());
		assertEquals(2, h.position().barsProcessed());
	}

	@Test
	void afterASellFailureNeitherTakeProfitNorStopLossIsJudgedAgain() {
		PaperPosition failed = holding(evaluate(atBar(base(), 1), bar(DAY, "9800", "9850", "9700", "9750"))).position();

		// 익절가를 넘는 봉도, 손절가 아래로 더 내려간 봉도 판정하지 않는다
		PaperExit.Holding up = holding(evaluate(failed, bar(DAY.plusDays(1), "10000", "10500", "9990", "10400")));
		assertEquals(PaperExit.Note.NONE, up.note());
		assertTrue(up.position().sellFailed());
		PaperExit.Holding down = holding(evaluate(up.position(), bar(DAY.plusDays(2), "9000", "9100", "8900", "9000")));
		assertEquals(PaperExit.Note.NONE, down.note());
		assertEquals(4, down.position().barsProcessed());
	}

	@Test
	void timeExitAtTheFifthBarSellsAtTheOpenMinusSlippageWithoutJudgingTakeProfit() {
		// 시가 10500은 익절가 위지만 5번째 봉에서는 익절을 판정하지 않는다. 10500 x 0.998 = 10479
		PaperTrade t = exited(evaluate(atBar(base(), 5), bar(DAY, "10500", "10600", "10400", "10500")));

		assertEquals(PaperTrade.ExitReason.TIME_EXIT, t.exitReason());
		assertEquals(0, bd("10479").compareTo(t.exitPrice()));
		assertFalse(t.gapExit());
	}

	@Test
	void theFourthBarIsStillJudgedNormally() {
		PaperTrade t = exited(evaluate(atBar(base(), 4), bar(DAY, "10500", "10600", "10400", "10500")));

		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, t.exitReason());
	}

	@Test
	void timeExitFillsEvenWhenTheOpenGapsBelowTheStopAndIsNotClampedToTheLow() {
		// 시가 = 저가 = 9000이라도 시장가라 체결되고, 9000 x 0.998 = 8982가 저가보다 낮아도 그대로 쓴다 (비관적인 쪽)
		PaperTrade t = exited(evaluate(atBar(base(), 5), bar(DAY, "9000", "9100", "9000", "9050")));

		assertEquals(PaperTrade.ExitReason.TIME_EXIT, t.exitReason());
		assertEquals(0, bd("8982").compareTo(t.exitPrice()));
	}

	@Test
	void timeExitAfterASellFailureIsStillATimeExit() {
		PaperPosition failed = holding(evaluate(atBar(base(), 1), bar(DAY, "9800", "9850", "9700", "9750"))).position();
		PaperTrade t = exited(evaluate(atBar(failed, 5), bar(DAY, "9000", "9100", "8900", "9000")));

		assertEquals(PaperTrade.ExitReason.TIME_EXIT, t.exitReason());
		assertTrue(t.sellFailed());
	}

	@Test
	void haltedDayFillsNothingEvenWhenThePriceWouldCrossTheStop() {
		PaperExit.Holding h = holding(evaluate(atBar(base(), 2), bar(DAY, "9000", "9000", "9000", "9000", "0")));

		assertEquals(PaperExit.Note.HALTED, h.note());
		assertEquals(3, h.position().barsProcessed());
		assertFalse(h.position().sellFailed());
	}

	@Test
	void timeExitWaitsForTheFirstBarWithVolumeAfterAHalt() {
		PaperExit.Holding halted = holding(evaluate(atBar(base(), 5), bar(DAY, "10000", "10000", "10000", "10000", "0")));
		assertEquals(PaperExit.Note.HALTED, halted.note());
		assertEquals(6, halted.position().barsProcessed());

		PaperTrade t = exited(evaluate(halted.position(), bar(DAY.plusDays(1), "10000", "10100", "9950", "10050")));
		assertEquals(PaperTrade.ExitReason.TIME_EXIT, t.exitReason());
		assertEquals(0, bd("9980").compareTo(t.exitPrice()));
	}

	@Test
	void exitOnTheEntryBarIsFlagged() {
		PaperTrade t = exited(evaluate(base(), bar(DAY, "10000", "10050", "9890", "9900")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertTrue(t.exitOnEntryDay());
	}

	@Test
	void tradeCarriesTheEntrySideFlagsAndSignalInfo() {
		Candle entryBar = bar(ENTRY_DATE, "10000", "10010", "9950", "10000");
		PaperPosition clamped = ((PaperEntry.Entered) PaperEntry.enter(
			PaperTestSupport.signal("005930", 7, "100", "9900"), ENTRY_DATE, entryBar, SLIPPAGE)).position();

		PaperTrade t = exited(evaluate(atBar(clamped, 1), bar(DAY, "10000", "10500", "9950", "10400")));

		assertTrue(t.entryClampedToHigh());
		assertEquals(7, t.rank());
		assertEquals(PaperTestSupport.SIGNAL_DATE, t.signalDate());
		assertEquals(ENTRY_DATE, t.entryDate());
		assertEquals(0, bd("10010").compareTo(t.entryPrice()));
	}

	@Test
	void stopOrderPriceExactlyEqualToTheHighStillFills() {
		// 시가 9800, 고가 9899 = 주문가 -> 체결 (고가가 주문가 이상이면 체결)
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "9800", "9899", "9700", "9850")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertEquals(0, bd("9899").compareTo(t.exitPrice()));
		assertTrue(t.gapExit());
	}

	@Test
	void aHighOneWonBelowTheStopOrderPriceIsASellFailure() {
		PaperExit.Holding h = holding(evaluate(atBar(base(), 1), bar(DAY, "9800", "9898", "9700", "9850")));

		assertEquals(PaperExit.Note.SELL_FAILED, h.note());
	}

	@Test
	void anOpenExactlyAtTheStopOrderPriceIsNotAGap() {
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "9899", "9950", "9890", "9900")));

		assertEquals(PaperTrade.ExitReason.STOP_LOSS, t.exitReason());
		assertFalse(t.gapExit());
	}

	@Test
	void anOpenExactlyAtTheTakeProfitPriceIsNotAGap() {
		PaperTrade t = exited(evaluate(atBar(base(), 1), bar(DAY, "10100", "10200", "10050", "10150")));

		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, t.exitReason());
		assertFalse(t.gapExit());
	}

	@Test
	void timeExitPriceRoundsHalfUp() {
		// 750 x 0.998 = 748.5 -> 749 (HALF_EVEN이면 748)
		PaperTrade t = exited(evaluate(atBar(base(), 5), bar(DAY, "750", "760", "740", "750")));

		assertEquals(0, bd("749").compareTo(t.exitPrice()));
	}

	@Test
	void processingTheSameBarTwiceIsRejected() {
		PaperPosition after = holding(evaluate(base(), bar(DAY, "10000", "10050", "9950", "10000"))).position();

		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> evaluate(after, bar(DAY, "10000", "10050", "9950", "10000")));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> evaluate(after, bar(DAY.minusDays(1), "10000", "10050", "9950", "10000")));
		// 다음 날 봉은 처리된다
		assertEquals(2, holding(evaluate(after, bar(DAY.plusDays(1), "10000", "10050", "9950", "10000"))).position().barsProcessed());
	}

	@Test
	void rejectsABarWhoseDateIsNotTheGivenDate() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> PaperExit.evaluate(base(), DAY.plusDays(1), bar(DAY, "10000", "10050", "9950", "10000"), SLIPPAGE));
	}

	@Test
	void rejectsInvalidSlippage() {
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
			() -> PaperExit.evaluate(base(), DAY, bar(DAY, "10000", "10050", "9950", "10000"), bd("1")));
	}
}
