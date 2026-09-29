package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.damdam.bot.papertrading.PaperTestSupport.ENTRY_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.bar;
import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static com.damdam.bot.papertrading.PaperTestSupport.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperEntryTest {

	private static final BigDecimal SLIPPAGE = bd("0.002");

	private static PaperPosition entered(PaperEntry.Result result) {
		return ((PaperEntry.Entered) result).position();
	}

	private static SkipReason skipped(PaperEntry.Result result) {
		return ((PaperEntry.Skipped) result).reason();
	}

	@Test
	void entersAtOpenPlusSlippageAndBuildsExitPricesFromThatEntryPrice() {
		// 시가 10000 x 1.002 = 10020. ATR 100 -> 익절 10120, 손절 감시 9920, 손절 주문 9919
		PaperPosition p = entered(PaperEntry.enter(signal("005930", 3, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE));

		assertEquals(0, bd("10020").compareTo(p.entryPrice()));
		assertEquals(0, bd("10120").compareTo(p.takeProfitPrice()));
		assertEquals(0, bd("9920").compareTo(p.stopTrigger()));
		assertEquals(0, bd("9919").compareTo(p.stopOrderPrice()));
		assertEquals(1, p.quantity());
		assertEquals(0, p.barsProcessed());
		assertFalse(p.sellFailed());
		assertFalse(p.entryClampedToHigh());
		assertEquals(3, p.rank());
		assertEquals(SIGNAL_DATE, p.signalDate());
		assertEquals(ENTRY_DATE, p.entryDate());
		assertEquals(0, bd("10200").compareTo(p.entryBarClose()));
		// 신호일 종가 대비 시가 갭: (10000 - 9900) / 9900 = 0.010101
		assertEquals(0, bd("0.010101").compareTo(p.entryGapRate()));
	}

	@Test
	void roundsEntryPriceToWholeWonHalfUp() {
		// 333 x 1.002 = 333.666 -> 334. ATR 1 -> 익절 335, 손절 감시 333, 손절 주문 332
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "1", "330"), ENTRY_DATE,
			bar(ENTRY_DATE, "333", "340", "330", "335"), SLIPPAGE));

		assertEquals(0, bd("334").compareTo(p.entryPrice()));
		assertEquals(0, bd("335").compareTo(p.takeProfitPrice()));
		assertEquals(0, bd("333").compareTo(p.stopTrigger()));
		assertEquals(0, bd("332").compareTo(p.stopOrderPrice()));
	}

	@Test
	void entryPriceRoundsHalfUpAtExactlyPointFive() {
		// 250 x 1.002 = 250.5 -> 251 (HALF_EVEN이면 250)
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "1", "240"), ENTRY_DATE,
			bar(ENTRY_DATE, "250", "260", "240", "255"), SLIPPAGE));

		assertEquals(0, bd("251").compareTo(p.entryPrice()));
	}

	@Test
	void rejectsABarWhoseDateIsNotTheEntryDate() {
		assertThrows(IllegalArgumentException.class, () -> PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE.plusDays(1), "10000", "10500", "9950", "10200"), SLIPPAGE));
	}

	@Test
	void zeroSlippageEntersAtTheOpen() {
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), BigDecimal.ZERO));

		assertEquals(0, bd("10000").compareTo(p.entryPrice()));
	}

	@Test
	void clampsEntryPriceToTheHighInsteadOfFailingTheEntry() {
		// 10000 x 1.002 = 10020 > 고가 10010 -> 진입가 10010, 익절 10110, 손절 감시 9910
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10010", "9950", "10000"), SLIPPAGE));

		assertTrue(p.entryClampedToHigh());
		assertEquals(0, bd("10010").compareTo(p.entryPrice()));
		assertEquals(0, bd("10110").compareTo(p.takeProfitPrice()));
		assertEquals(0, bd("9910").compareTo(p.stopTrigger()));
	}

	@Test
	void entryPriceEqualToTheHighIsNotClamped() {
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10020", "9950", "10000"), SLIPPAGE));

		assertFalse(p.entryClampedToHigh());
	}

	@Test
	void skipsWhenEntryBarVolumeIsZero() {
		assertEquals(SkipReason.ZERO_VOLUME, skipped(PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10000", "10000", "10000", "0"), SLIPPAGE)));
	}

	@Test
	void skipsWhenEntryPriceMinusAtrIsNotPositive() {
		// 진입가 10020 - ATR 10020 = 0
		assertEquals(SkipReason.INVALID_EXIT_PRICES, skipped(PaperEntry.enter(signal("X", 1, "10020", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE)));
		assertEquals(SkipReason.INVALID_EXIT_PRICES, skipped(PaperEntry.enter(signal("X", 1, "20000", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE)));
	}

	@Test
	void skipsWhenAtrIsTooSmallForExitPricesToDifferFromTheEntryPrice() {
		// ATR 0.4 -> 익절가 round(10020.4) = 10020 = 진입가
		assertEquals(SkipReason.INVALID_EXIT_PRICES, skipped(PaperEntry.enter(signal("X", 1, "0.4", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE)));
	}

	@Test
	void skipsWhenTheStopOrderPriceWouldBeZeroOrLess() {
		// 진입가 3 - ATR 2 = 1 > 0 이지만 손절 주문가 1 - 1 = 0
		assertEquals(SkipReason.INVALID_EXIT_PRICES, skipped(PaperEntry.enter(signal("X", 1, "2", "3"), ENTRY_DATE,
			bar(ENTRY_DATE, "3", "4", "3", "3"), BigDecimal.ZERO)));
	}

	@Test
	void rejectsAnEntryDateThatIsNotAfterTheSignalDate() {
		assertThrows(IllegalArgumentException.class, () -> PaperEntry.enter(signal("X", 1, "100", "9900"), SIGNAL_DATE,
			bar(SIGNAL_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE));
	}

	@Test
	void rejectsInvalidSlippage() {
		assertThrows(IllegalArgumentException.class, () -> PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), bd("-0.001")));
		assertThrows(IllegalArgumentException.class, () -> PaperEntry.enter(signal("X", 1, "100", "9900"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), BigDecimal.ONE));
	}

	@Test
	void gapRateIsZeroWhenTheSignalCloseIsZero() {
		PaperPosition p = entered(PaperEntry.enter(signal("X", 1, "100", "0"), ENTRY_DATE,
			bar(ENTRY_DATE, "10000", "10500", "9950", "10200"), SLIPPAGE));

		assertEquals(0, BigDecimal.ZERO.compareTo(p.entryGapRate()));
	}
}
