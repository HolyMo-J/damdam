package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.PendingSignal;
import com.damdam.bot.papertrading.SignalScan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.basis;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.bd;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kst;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.row;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PendingSignalsTest {

	private static final Instant UPDATED = kst("2026-10-02T20:21:22");
	private static final Instant SCANNED = kst("2026-10-02T21:00:00");

	@Test
	void buildsOnlyTheRequestedStrategysSignalsInRankOrderWithAtrAndClose() {
		SignalScan scan = scan(SCANNED,
			row(1, "AAA", true, false, UPDATED, basis("100", "10000")),
			row(2, "BBB", false, true, UPDATED, basis("200", "20000")),
			row(3, "CCC", true, true, UPDATED, basis("300", "30000")));

		PendingSignals.Built b = PendingSignals.build(Strategies.ICHIMOKU, scan);
		PendingSignals.Built a = PendingSignals.build(Strategies.INSTITUTION, scan);

		assertEquals(List.of(
			new PendingSignal("B", "AAA", 1, SIGNAL_DATE, bd("100"), bd("10000")),
			new PendingSignal("B", "CCC", 3, SIGNAL_DATE, bd("300"), bd("30000"))), b.signals());
		assertEquals(List.of(
			new PendingSignal("A", "BBB", 2, SIGNAL_DATE, bd("200"), bd("20000")),
			new PendingSignal("A", "CCC", 3, SIGNAL_DATE, bd("300"), bd("30000"))), a.signals());
		assertEquals(List.of(), b.missingBasis());
	}

	@Test
	void signaledRowWithoutAtrAndCloseIsReportedNotSilentlyDropped() {
		SignalScan scan = scan(SCANNED,
			row(1, "AAA", true, false, UPDATED, null),
			row(2, "BBB", true, false, UPDATED, basis("100", "10000")));

		PendingSignals.Built built = PendingSignals.build(Strategies.ICHIMOKU, scan);

		assertEquals(List.of("BBB"), built.signals().stream().map(PendingSignal::symbol).toList());
		assertEquals(List.of("AAA"), built.missingBasis());
	}

	@Test
	void noSignalsGivesAnEmptyListNotAnError() {
		SignalScan scan = scan(SCANNED, row(1, "AAA", false, false, UPDATED, basis("100", "10000")));

		assertEquals(List.of(), PendingSignals.build(Strategies.ICHIMOKU, scan).signals());
	}

	@Test
	void unknownStrategyIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> PendingSignals.build("Z", scan(SCANNED)));
	}
}
