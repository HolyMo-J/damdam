package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.PaperLedger;
import com.damdam.bot.papertrading.PaperStrategyState;
import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.StrategyOutcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.basis;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kst;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.row;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunReportTest {

	private static PaperLedger.Report report(String strategy, List<LocalDate> settled, List<String> unsettled,
			List<String> abandoned, boolean systemic) {
		return new PaperLedger.Report(PaperStrategyState.initial(strategy), settled, unsettled, abandoned, systemic);
	}

	private static RunReport.StrategyResult ok(String strategy, int pending) {
		return new RunReport.StrategyResult(strategy, report(strategy, List.of(SIGNAL_DATE), List.of(), List.of(), false), null, pending,
			List.of(), false);
	}

	@Test
	void aCleanRunIsAShortSummaryWithoutAnAttentionMark() {
		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(ok("A", 2), ok("B", 0)),
			scan(kst("2026-10-02T21:00:00"), row(1, "AAA", true, true, kst("2026-10-02T20:21:22"), basis("100", "10000"))), List.of());

		assertFalse(message.attention());
		assertTrue(message.text().startsWith("가상매매 실행 결과 (신호일 2026-10-02)"));
		assertTrue(message.text().contains("전략 A: 정산 1거래일, 대기 신호 2건 저장"));
		assertTrue(message.text().contains("전략 B: 정산 1거래일, 대기 신호 0건 저장"));
		assertTrue(message.text().contains("신호 판정: 종목 1개"));
	}

	@Test
	void anUnsettledSymbolAndASignalGapAreMarkedForAttention() {
		RunReport.StrategyResult a = new RunReport.StrategyResult("A",
			report("A", List.of(), List.of("111111"), List.of(), false), "정산이 신호일 2026-10-02까지 끝나지 않았습니다", 0, List.of(), false);

		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(a, ok("B", 1)), null, List.of());

		assertTrue(message.attention());
		assertTrue(message.text().startsWith("[주의] "));
		assertTrue(message.text().contains("미확정 종목 111111"));
		assertTrue(message.text().contains("신호 공백: 정산이"));
		assertFalse(message.text().contains("신호 판정:"));   // 스캔을 못 돌렸으면 판정 줄이 없다
	}

	@Test
	void abandonedSymbolsSystemicMissAndMissingBasisAreEachReported() {
		RunReport.StrategyResult a = new RunReport.StrategyResult("A",
			report("A", List.of(SIGNAL_DATE), List.of(), List.of("222222"), false), null, 1, List.of(), false);
		RunReport.StrategyResult b = new RunReport.StrategyResult("B",
			report("B", List.of(), List.of("333333"), List.of(), true), "정산 실패", 0, List.of("444444"), false);

		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(a, b), null, List.of());

		assertTrue(message.attention());
		assertTrue(message.text().contains("이번에 포기한 종목 222222"));
		assertTrue(message.text().contains("봉 조회 전체 결측으로 멈춤"));
		assertTrue(message.text().contains("대기 신호를 만들지 못한 종목 444444"));
	}

	@Test
	void aStrategyWhoseSettlementDidNotRunSaysSo() {
		RunReport.StrategyResult a = new RunReport.StrategyResult("A", null, "정산 전에 실패", 0, List.of(), false);

		assertTrue(RunReport.format(SIGNAL_DATE, List.of(a), null, List.of()).text().contains("전략 A: 정산하지 못함"));
	}

	@Test
	void anAlreadySavedStrategyIsReportedAsSkippedWithoutAnAttentionMark() {
		RunReport.StrategyResult saved = new RunReport.StrategyResult("B",
			report("B", List.of(), List.of(), List.of(), false), null, 3, List.of(), true);

		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(saved), null, List.of());

		assertFalse(message.attention());
		assertTrue(message.text().contains("이미 저장된 대기 신호 3건이 있어 다시 판정하지 않음"));
		assertFalse(message.text().contains("대기 신호 3건 저장"));
	}

	@Test
	void warningsAreListedAndMarkTheRunForAttention() {
		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(ok("A", 0)), null, List.of("scan_rows.csv 기록에 실패했습니다"));

		assertTrue(message.attention());
		assertTrue(message.text().contains("경고: scan_rows.csv 기록에 실패했습니다"));
	}

	@Test
	void aFetchFailureInTheScanMarksAttentionEvenWhenCoverageIsAboveTheScanThreshold() {
		SignalScan.Row failed = new SignalScan.Row(2, "CCC", "이름CCC",
			new StrategyOutcome<>(StrategyOutcome.Status.FETCH_FAILED, null, "HTTP 500"),
			new StrategyOutcome<>(StrategyOutcome.Status.FETCH_FAILED, null, "HTTP 500"), null, null);
		SignalScan scan = scan(kst("2026-10-02T21:00:00"),
			row(1, "AAA", false, false, kst("2026-10-02T20:21:22"), basis("100", "10000")), failed);

		RunReport.Message message = RunReport.format(SIGNAL_DATE, List.of(ok("A", 0), ok("B", 0)), scan, List.of());

		assertTrue(message.attention());
		assertTrue(message.text().contains("조회 실패 1"));
	}
}
