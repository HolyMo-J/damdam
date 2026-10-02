package com.damdam.bot.papertrading;

import com.damdam.bot.market.MarketCalendarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignalScanRunnerTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 2026-10-03(토)과 04(일)은 주말, 05(월)은 개천절 대체공휴일이라 영업일이 아니다
	private static final Predicate<LocalDate> TRADING = date -> date.getDayOfWeek().getValue() <= 5
		&& !date.equals(LocalDate.of(2026, 10, 5));

	private static ZonedDateTime at(String isoLocal) {
		return java.time.LocalDateTime.parse(isoLocal).atZone(SEOUL);
	}

	@Test
	void beforeTheConfirmationTimeTheDefaultSignalDateIsThePreviousTradingDay() {
		// 금요일 01:51은 아직 어제(목) 저녁 값만 확정된 시각이다
		assertEquals(LocalDate.of(2026, 10, 1), SignalDates.defaultSignalDate(at("2026-10-02T01:51:00"), TRADING));
		assertEquals(LocalDate.of(2026, 10, 1), SignalDates.defaultSignalDate(at("2026-10-02T20:29:59"), TRADING));
	}

	@Test
	void fromTheConfirmationTimeOnATradingDayTheDefaultSignalDateIsToday() {
		assertEquals(LocalDate.of(2026, 10, 2), SignalDates.defaultSignalDate(at("2026-10-02T20:30:00"), TRADING));
		assertEquals(LocalDate.of(2026, 10, 2), SignalDates.defaultSignalDate(at("2026-10-02T23:59:00"), TRADING));
	}

	@Test
	void weekendsAndHolidaysFallBackToTheLastTradingDay() {
		// 토요일, 일요일, 휴장인 월요일(10/5)은 저녁이어도 직전 영업일(금 10/2)이다
		assertEquals(LocalDate.of(2026, 10, 2), SignalDates.defaultSignalDate(at("2026-10-03T21:00:00"), TRADING));
		assertEquals(LocalDate.of(2026, 10, 2), SignalDates.defaultSignalDate(at("2026-10-05T21:00:00"), TRADING));
		// 휴장 다음 영업일(화 10/6) 아침은 금 10/2다
		assertEquals(LocalDate.of(2026, 10, 2), SignalDates.defaultSignalDate(at("2026-10-06T09:00:00"), TRADING));
	}

	@Test
	void failsWhenNoTradingDayIsFoundInTheLookbackWindow() {
		assertThrows(IllegalStateException.class, () -> SignalDates.defaultSignalDate(at("2026-10-02T21:00:00"), date -> false));
	}

	@Test
	void signalDateArgumentIsParsedAndABadOneIsRejected() {
		assertEquals(Optional.of(LocalDate.of(2026, 10, 1)), SignalScanRunner.signalDateArg("--signal-date=2026-10-01"));
		assertEquals(Optional.empty(), SignalScanRunner.signalDateArg("--other=1"));
		assertThrows(IllegalArgumentException.class, () -> SignalScanRunner.signalDateArg("--signal-date=어제"));
	}

	private static final LocalDate SIGNAL_DATE = LocalDate.of(2026, 10, 1);

	private static IchimokuCloudBreakout.Result ichimokuResult(boolean cross, boolean surge) {
		return new IchimokuCloudBreakout.Result(SIGNAL_DATE, cross, surge, new BigDecimal("71000"), new BigDecimal("70500"),
			new BigDecimal("70000"), new BigDecimal("70400"), new BigDecimal("3000000"), new BigDecimal("1000000.0000"));
	}

	private static InstitutionNetBuySignal.Result institutionResult(boolean consecutive, boolean ratio) {
		return new InstitutionNetBuySignal.Result(SIGNAL_DATE, consecutive, ratio, new BigDecimal("1500000"),
			new BigDecimal("30000000"), new BigDecimal("5.0000"));
	}

	private static SignalScan.Row row(int rank, String symbol, String name, StrategyOutcome<IchimokuCloudBreakout.Result> b,
			StrategyOutcome<InstitutionNetBuySignal.Result> a, Instant updatedAt) {
		return new SignalScan.Row(rank, symbol, name, b, a, updatedAt, null);
	}

	private static SignalScan sampleScan() {
		Instant updated = Instant.parse("2026-10-01T11:21:22Z"); // 서울 20:21:22
		List<SignalScan.Row> rows = List.of(
			row(1, "005930", "삼성전자", StrategyOutcome.evaluated(ichimokuResult(true, true)),
				StrategyOutcome.evaluated(institutionResult(true, true)), updated),
			row(2, "000660", "SK하이닉스", StrategyOutcome.evaluated(ichimokuResult(true, false)),
				StrategyOutcome.evaluated(institutionResult(true, false)), updated),
			row(3, "035420", "NAVER", StrategyOutcome.evaluated(ichimokuResult(true, true)),
				StrategyOutcome.fetchFailed("조회 실패"), null),
			row(4, "123456", "신규상장", StrategyOutcome.insufficientCandles("봉 부족"),
				StrategyOutcome.evaluated(institutionResult(true, true)), updated));
		return new SignalScan(SIGNAL_DATE, Instant.parse("2026-10-01T16:51:00Z"), rows);
	}

	private static TargetUniverse sampleUniverse() {
		return new TargetUniverse(List.of(new TargetUniverse.Member(1, "005930", "삼성전자", "1")),
			List.of(new TargetUniverse.Exclusion(9, "999999", ExclusionReason.WARNING_LOOKUP_FAILED)), null);
	}

	@Test
	void describeListsOnlyTheSignaledRowsWithTheirEvidenceAndCountsTheUnjudgeable() {
		List<String> lines = SignalScanRunner.describe(sampleUniverse(), sampleScan(), at("2026-10-02T01:51:00"));
		String text = String.join("\n", lines);

		// 전략 B 신호: 삼성전자와 NAVER만 (SK하이닉스는 거래량 조건 미충족, 신규상장은 판정 불가)
		assertTrue(text.contains("[전략 B: 일목 구름 상향 돌파 + 거래량 2배] 신호 2건 (판정 3, 봉 부족 1, 데이터 오류 0, 조회 실패 0)"), text);
		assertTrue(text.contains("순위 1 삼성전자(005930) 종가 71000 > 구름 상단 70500 (전날 종가 70000 <= 구름 상단 70400), "
			+ "거래량 3000000 = 직전 20일 평균 1000000의 3.00배"), text);
		assertTrue(text.contains("순위 3 NAVER(035420)"), text);
		assertFalse(text.contains("순위 2 SK하이닉스(000660) 종가"), text);
		// 전략 A 신호: 삼성전자와 신규상장 (NAVER는 조회 실패)
		assertTrue(text.contains("[전략 A: 기관 3일 연속 순매수 + 거래량 3% 이상] 신호 2건 (판정 3, 봉 부족 0, 데이터 오류 0, 조회 실패 1)"), text);
		assertTrue(text.contains("순위 4 신규상장(123456) 3일 기관 순매수 합 1500000 / 같은 기간 거래량 합 30000000 = 5% (기록 갱신 2026-10-01 20:21:22)"), text);
		assertTrue(text.contains("[두 전략 모두 신호] [005930]"), text);
		assertTrue(text.contains("매수 추천이 아닙니다"), text);
		assertTrue(text.contains("[대상 종목군] 1개 (제외 1개), 판정 시각 2026-10-02 01:51:00 (KST)"), text);
	}

	@Test
	void describeWarnsWhenTheSignalDateIsTodayBeforeTheConfirmationTime() {
		SignalScan todayScan = new SignalScan(LocalDate.of(2026, 10, 2), Instant.parse("2026-10-02T03:00:00Z"), sampleScan().rows());

		String early = String.join("\n", SignalScanRunner.describe(sampleUniverse(), todayScan, at("2026-10-02T12:00:00")));
		String late = String.join("\n", SignalScanRunner.describe(sampleUniverse(), todayScan, at("2026-10-02T21:00:00")));

		assertTrue(early.contains("[주의] 신호일이 오늘인데"), early);
		assertFalse(late.contains("[주의]"), late);
	}

	@Test
	void describeWithoutAnySignalSaysZeroInsteadOfStayingSilent() {
		SignalScan none = new SignalScan(SIGNAL_DATE, Instant.parse("2026-10-01T16:51:00Z"), List.of(
			row(1, "005930", "삼성전자", StrategyOutcome.evaluated(ichimokuResult(false, false)),
				StrategyOutcome.evaluated(institutionResult(false, false)), null)));

		String text = String.join("\n", SignalScanRunner.describe(sampleUniverse(), none, at("2026-10-02T01:51:00")));

		assertTrue(text.contains("신호 0건 (판정 1"), text);
		assertFalse(text.contains("[두 전략 모두 신호]"), text);
	}

	@Test
	void runScansTheDefaultSignalDateAndAppendsToTheReport(@TempDir Path dir) throws IOException {
		TargetUniverseService universeService = mock(TargetUniverseService.class);
		SignalScanService scanService = mock(SignalScanService.class);
		MarketCalendarService calendar = mock(MarketCalendarService.class);
		when(calendar.isTradingDay(any())).thenAnswer(invocation -> TRADING.test(invocation.getArgument(0)));
		TargetUniverse universe = sampleUniverse();
		when(universeService.build()).thenReturn(universe);
		when(scanService.scan(universe, SIGNAL_DATE)).thenReturn(sampleScan());
		Clock clock = Clock.fixed(Instant.parse("2026-10-01T16:51:00Z"), SEOUL); // 서울 2026-10-02 01:51
		Path report = dir.resolve("probe/signal-scan.log");

		SignalScanRunner runner = new SignalScanRunner(universeService, scanService, calendar, report, clock);
		runner.run();
		runner.run();

		verify(scanService, org.mockito.Mockito.times(2)).scan(universe, SIGNAL_DATE);
		List<String> saved = Files.readAllLines(report, StandardCharsets.UTF_8);
		assertEquals(2, saved.stream().filter(l -> l.startsWith("=== 신호 판정 시작")).count());
		assertTrue(saved.get(0).contains("신호일 2026-10-01"), saved.get(0));
		assertTrue(saved.stream().anyMatch(l -> l.contains("순위 1 삼성전자(005930)")));
		assertEquals("=== 판정 끝 ===", saved.get(saved.size() - 1));
	}

	@Test
	void runRecordsTheFailureInTheReportAndRethrows(@TempDir Path dir) throws IOException {
		TargetUniverseService universeService = mock(TargetUniverseService.class);
		SignalScanService scanService = mock(SignalScanService.class);
		MarketCalendarService calendar = mock(MarketCalendarService.class);
		when(calendar.isTradingDay(any())).thenReturn(true);
		when(universeService.build()).thenThrow(new IllegalStateException("거래대금 순위가 비어 있습니다"));
		Path report = dir.resolve("signal-scan.log");
		Clock clock = Clock.fixed(Instant.parse("2026-10-01T16:51:00Z"), SEOUL);

		SignalScanRunner runner = new SignalScanRunner(universeService, scanService, calendar, report, clock);

		assertThrows(IllegalStateException.class, runner::run);
		String saved = Files.readString(report, StandardCharsets.UTF_8);
		assertTrue(saved.contains("[실패] 거래대금 순위가 비어 있습니다"), saved);
		assertTrue(saved.contains("=== 판정 끝 ==="), saved);
	}

	@Test
	void runWithAnExplicitSignalDateSkipsTheCalendarLookup(@TempDir Path dir) {
		TargetUniverseService universeService = mock(TargetUniverseService.class);
		SignalScanService scanService = mock(SignalScanService.class);
		MarketCalendarService calendar = mock(MarketCalendarService.class);
		TargetUniverse universe = sampleUniverse();
		when(universeService.build()).thenReturn(universe);
		when(scanService.scan(universe, LocalDate.of(2026, 9, 30))).thenReturn(
			new SignalScan(LocalDate.of(2026, 9, 30), Instant.parse("2026-10-01T16:51:00Z"), sampleScan().rows()));
		Clock clock = Clock.fixed(Instant.parse("2026-10-01T16:51:00Z"), SEOUL);

		new SignalScanRunner(universeService, scanService, calendar, dir.resolve("r.log"), clock).run("--signal-date=2026-09-30");

		verify(scanService).scan(universe, LocalDate.of(2026, 9, 30));
		org.mockito.Mockito.verifyNoInteractions(calendar);
	}
}
