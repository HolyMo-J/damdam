package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static com.damdam.bot.papertrading.PaperTestSupport.ENTRY_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.bar;
import static com.damdam.bot.papertrading.PaperTestSupport.signal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 슬리피지 0으로 돌려서 손계산이 쉽다: 시가 10000, ATR 100이면 익절가 10100, 손절 감시가 9900, 손절 주문가 9899
class PaperLedgerTest {

	private static final String SYMBOL = "005930";
	private static final String REFERENCE = "000660"; // 기준 종목: 이 종목의 봉이 오면 "조회 전체가 죽은 것은 아니다"
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final LocalDate DAY_3 = ENTRY_DATE.plusDays(1); // 수요일
	private static final LocalDate DAY_4 = ENTRY_DATE.plusDays(2); // 목요일

	// 달력 날짜가 바뀌는 것을 흉내 내려고 시각을 옮길 수 있는 시계
	private static final class TestClock extends Clock {
		private Instant now = Instant.parse("2026-09-30T11:00:00Z"); // 서울 20:00

		void nextDay() {
			now = now.plus(Duration.ofDays(1));
		}

		@Override
		public ZoneId getZone() {
			return SEOUL;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@TempDir
	Path dir;

	private TestClock clock;
	private PaperRunLock lock;
	private PaperLedger ledger;
	private Path stateFile;

	@BeforeEach
	void setUp() {
		clock = new TestClock();
		lock = PaperRunLock.tryAcquire(dir.resolve("paper.lock")).orElseThrow();
		ledger = ledger(BigDecimal.ZERO, "v1");
		stateFile = dir.resolve("data/state_B.json");
	}

	@AfterEach
	void tearDown() {
		lock.close();
	}

	private PaperLedger ledger(BigDecimal slippage, String version) {
		return new PaperLedger(dir.resolve("data"), dir.resolve("records"), version, slippage, clock, lock);
	}

	private static SortedMap<LocalDate, Map<String, Candle>> day(LocalDate date, Map<String, Candle> bars) {
		SortedMap<LocalDate, Map<String, Candle> > map = new TreeMap<>();
		map.put(date, bars);
		return map;
	}

	private static Map<String, Candle> flat(LocalDate date) {
		return Map.of(SYMBOL, bar(date, "10000", "10000", "10000", "10000"));
	}

	// 대상 종목의 봉은 없고 기준 종목의 봉만 온 날
	private static Map<String, Candle> referenceOnly(LocalDate date) {
		return Map.of(REFERENCE, bar(date, "20000", "20000", "20000", "20000"));
	}

	// 9/28을 빈 정산으로 확정하고 그날 신호를 대기 신호로 저장해 진입 준비를 한다
	private void settleSignalDayWithOneSignal() {
		assertTrue(ledger.settleDays("B", day(SIGNAL_DATE, Map.of())).complete());
		ledger.recordPending("B", SIGNAL_DATE, List.of(signal(SYMBOL, 1, "100", "10000")));
	}

	private List<String> csv(String name) throws IOException {
		return Files.readAllLines(dir.resolve("records").resolve(name), StandardCharsets.UTF_8);
	}

	@Test
	void enterHoldAndTakeProfitAcrossDaysWritesStateAndRecords() throws IOException {
		settleSignalDayWithOneSignal();

		PaperLedger.Report entered = ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));

		assertTrue(entered.complete());
		assertEquals(List.of(ENTRY_DATE), entered.settledDates());
		assertEquals(1, entered.state().positions().size());
		assertEquals(List.of(), entered.state().pending());
		assertEquals(ENTRY_DATE, entered.state().settledThroughDate());
		// 진입 봉에서는 익절도 손절도 나지 않았으므로 청산 기록은 아직 없다
		assertFalse(Files.exists(dir.resolve("records/trades_B.csv")));

		Map<String, Candle> up = Map.of(SYMBOL, bar(DAY_3, "10000", "10200", "9950", "10100"));
		PaperLedger.Report exited = ledger.settleDays("B", day(DAY_3, up));

		assertTrue(exited.complete());
		assertEquals(List.of(), exited.state().positions());
		assertEquals(1, exited.state().closedTrades().size());
		assertEquals(PaperTrade.ExitReason.TAKE_PROFIT, exited.state().closedTrades().get(0).exitReason());

		List<String> trades = csv("trades_B.csv");
		assertEquals(2, trades.size());
		assertTrue(trades.get(1).startsWith("B,005930,2026-09-28,2026-09-29,2026-09-30,1,1,10000,10100,TAKE_PROFIT,"), trades.get(1));
		assertTrue(trades.get(1).endsWith(",daily_batch,open_plus_slippage,0,v1"), trades.get(1));

		// 정산에 쓴 봉은 진입 날(대기 신호 종목)과 보유 중 판정 날 두 번 남고, 실행 상태는 정산한 거래일마다 한 줄이다
		assertEquals(3, csv("bars.csv").size());
		assertEquals(4, csv("runs.csv").size());
		assertTrue(csv("runs.csv").get(3).contains(",2026-09-30,SETTLED,1,0,0,"), csv("runs.csv").get(3));
	}

	@Test
	void recordPendingWritesTheSignalInputsToACsvThatSurvivesExit() throws IOException {
		settleSignalDayWithOneSignal();

		assertEquals(List.of("strategy,symbol,signal_date,rank,atr,signal_close", "B,005930,2026-09-28,1,100,10000"),
			csv("signals_B.csv"));
	}

	@Test
	void recordPendingRerunWithTheSameSignalsKeepsOneRowButChangedInputsFailClosed() throws IOException {
		settleSignalDayWithOneSignal();

		ledger.recordPending("B", SIGNAL_DATE, List.of(signal(SYMBOL, 1, "100", "10000")));
		assertEquals(2, csv("signals_B.csv").size());

		// 같은 키에 ATR이 다르면 어느 쪽이 맞는지 알 수 없으므로 상태도 바꾸지 않고 멈춘다
		assertThrows(IllegalStateException.class,
			() -> ledger.recordPending("B", SIGNAL_DATE, List.of(signal(SYMBOL, 1, "120", "10000"))));
		assertEquals(new BigDecimal("100"), ledger.loadState("B").pending().get(0).atr());
	}

	@Test
	void crashBetweenCsvAndStateWriteRecomputesTheDayWithoutDuplicatingRows() throws IOException {
		settleSignalDayWithOneSignal();
		ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));
		byte[] stateBeforeDay3 = Files.readAllBytes(stateFile);
		Map<String, Candle> up = Map.of(SYMBOL, bar(DAY_3, "10000", "10200", "9950", "10100"));

		PaperStrategyState firstRun = ledger.settleDays("B", day(DAY_3, up)).state();
		// 죽은 것처럼 상태 파일만 그날 정산 전으로 되돌린다 (CSV에는 이미 기록된 상태)
		Files.write(stateFile, stateBeforeDay3);
		assertEquals(ENTRY_DATE, ledger.loadState("B").settledThroughDate());

		PaperStrategyState secondRun = ledger.settleDays("B", day(DAY_3, up)).state();

		assertEquals(firstRun, secondRun);
		assertEquals(2, csv("trades_B.csv").size());
		assertEquals(3, csv("bars.csv").size());
		// 실행 이력은 같은 시각이면 같은 줄이라 무시된다 (운영에서는 실행 시각이 달라 줄이 늘고, 분석은 정산일별 마지막 SETTLED 행을 쓴다)
		assertEquals(4, csv("runs.csv").size());
	}

	@Test
	void rerunWithDifferentBarsAfterACrashFailsClosedInsteadOfSplittingCsvAndState() throws IOException {
		settleSignalDayWithOneSignal();
		ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));
		byte[] stateBeforeDay3 = Files.readAllBytes(stateFile);
		ledger.settleDays("B", day(DAY_3, Map.of(SYMBOL, bar(DAY_3, "10000", "10200", "9950", "10100"))));
		Files.write(stateFile, stateBeforeDay3);

		// 잠정치였던 봉이 확정치로 바뀌어 이번에는 손절이 난다: CSV에는 익절 행이 남아 있다
		Map<String, Candle> revised = Map.of(SYMBOL, bar(DAY_3, "10000", "10050", "9800", "9900"));
		assertThrows(IllegalStateException.class, () -> ledger.settleDays("B", day(DAY_3, revised)));

		assertEquals(ENTRY_DATE, ledger.loadState("B").settledThroughDate());
		assertTrue(csv("trades_B.csv").get(1).contains(",TAKE_PROFIT,"));
	}

	@Test
	void incompleteDayIsNotSavedButAttemptsAreCounted() throws IOException {
		settleSignalDayWithOneSignal();
		PaperStrategyState before = ledger.loadState("B");

		PaperLedger.Report report = ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE)));

		assertFalse(report.complete());
		assertFalse(report.systemicMiss());
		assertEquals(List.of(SYMBOL), report.unsettledSymbols());
		assertEquals(List.of(), report.settledDates());
		// 시도 횟수와 센 날짜 말고는 직전 확정 상태와 똑같다 (확정일, 대기 신호, 포지션이 그대로)
		assertEquals(before.withAttempts(Map.of(SYMBOL, 1), LocalDate.of(2026, 9, 30)), ledger.loadState("B"));
		assertFalse(Files.exists(dir.resolve("records/trades_B.csv")));
		assertTrue(csv("runs.csv").stream().anyMatch(l -> l.contains(",INCOMPLETE,")), csv("runs.csv").toString());
	}

	@Test
	void retriesOnTheSameCalendarDayDoNotCountTowardsAbandonment() {
		settleSignalDayWithOneSignal();

		// 작업 스케줄러 재시도나 수동 재실행이 같은 날 여러 번 있어도 하루로 센다
		for (int i = 0; i < 5; i++) {
			assertFalse(ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE))).complete());
		}
		assertEquals(Map.of(SYMBOL, 1), ledger.loadState("B").unsettledAttempts());
		assertEquals(1, ledger.loadState("B").pending().size());

		clock.nextDay();
		assertFalse(ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE))).complete());
		assertEquals(Map.of(SYMBOL, 2), ledger.loadState("B").unsettledAttempts());
	}

	@Test
	void pendingSignalIsAbandonedOnTheThirdConsecutiveMissDayAndRecordedAsNoBar() throws IOException {
		settleSignalDayWithOneSignal();

		assertFalse(ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE))).complete());
		assertEquals(Map.of(SYMBOL, 1), ledger.loadState("B").unsettledAttempts());
		clock.nextDay();
		assertFalse(ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE))).complete());
		// 두 번째 날까지는 포기하지 않는다 (경계)
		assertEquals(Map.of(SYMBOL, 2), ledger.loadState("B").unsettledAttempts());
		assertEquals(1, ledger.loadState("B").pending().size());
		assertFalse(Files.exists(dir.resolve("records/skips_B.csv")));

		clock.nextDay();
		PaperLedger.Report third = ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE)));

		assertTrue(third.complete());
		assertEquals(List.of(SYMBOL), third.abandonedSymbols());
		assertEquals(List.of(ENTRY_DATE), third.settledDates());
		assertEquals(List.of(), third.state().pending());
		assertEquals(Map.of(), third.state().unsettledAttempts());
		List<String> skips = csv("skips_B.csv");
		assertEquals(2, skips.size());
		assertTrue(skips.get(1).startsWith("B,005930,2026-09-28,NO_BAR,1,2026-09-29,100,10000,"), skips.get(1));
	}

	@Test
	void heldPositionIsMovedToAbandonedAndWrittenToItsOwnCsv() throws IOException {
		settleSignalDayWithOneSignal();
		ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));
		assertEquals(1, ledger.loadState("B").positions().size());

		assertFalse(ledger.settleDays("B", day(DAY_3, referenceOnly(DAY_3))).complete());
		clock.nextDay();
		assertFalse(ledger.settleDays("B", day(DAY_3, referenceOnly(DAY_3))).complete());
		clock.nextDay();
		PaperLedger.Report third = ledger.settleDays("B", day(DAY_3, referenceOnly(DAY_3)));

		assertTrue(third.complete());
		assertEquals(List.of(SYMBOL), third.abandonedSymbols());
		assertEquals(List.of(), third.state().positions());
		assertEquals(1, third.state().abandoned().size());
		assertEquals(SYMBOL, third.state().abandoned().get(0).position().symbol());
		assertEquals(DAY_3, third.state().abandoned().get(0).abandonedOn());
		assertEquals(DAY_3, third.state().settledThroughDate());
		// 포기한 포지션은 거래로 세지 않는다 (청산되지 않은 포지션으로 병기한다)
		assertEquals(List.of(), third.state().closedTrades());
		// 대신 진입가와 수량과 마지막 처리 봉이 CSV에 남아서 분석 쪽이 통계에 반영할 수 있다
		assertEquals(List.of(
			"strategy,symbol,signal_date,entry_date,abandoned_on,rank,quantity,entry_price,take_profit_price,stop_trigger,atr,"
				+ "bars_processed,last_bar_date,execution_mode,slippage_rate,strategy_version",
			"B,005930,2026-09-28,2026-09-29,2026-09-30,1,1,10000,10100,9900,100,1,2026-09-29,daily_batch,0,v1"),
			csv("abandoned_B.csv"));
	}

	@Test
	void aWholeDayWithoutAnyBarIsASystemMissAndNeverCountsOrAbandons() throws IOException {
		settleSignalDayWithOneSignal();
		ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));
		PaperStrategyState before = ledger.loadState("B");

		// 403이나 장애로 조회가 통째로 실패했거나 휴장일을 거래일로 넘긴 경우: 며칠이 지나도 포기하지 않는다
		for (int i = 0; i < 6; i++) {
			PaperLedger.Report report = ledger.settleDays("B", day(DAY_3, Map.of()));
			assertFalse(report.complete());
			assertTrue(report.systemicMiss());
			assertEquals(List.of(), report.abandonedSymbols());
			clock.nextDay();
		}

		assertEquals(before, ledger.loadState("B"));
		assertTrue(csv("runs.csv").stream().anyMatch(l -> l.contains("no_bars_at_all_not_counted")), csv("runs.csv").toString());
		assertFalse(Files.exists(dir.resolve("records/abandoned_B.csv")));
	}

	@Test
	void aSymbolThatReturnsBeforeTheThirdMissDayResetsItsAttempts() {
		settleSignalDayWithOneSignal();
		ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE)));
		clock.nextDay();
		ledger.settleDays("B", day(ENTRY_DATE, referenceOnly(ENTRY_DATE)));
		assertEquals(Map.of(SYMBOL, 2), ledger.loadState("B").unsettledAttempts());
		clock.nextDay();

		PaperLedger.Report recovered = ledger.settleDays("B", day(ENTRY_DATE, flat(ENTRY_DATE)));

		assertTrue(recovered.complete());
		assertEquals(List.of(), recovered.abandonedSymbols());
		assertEquals(1, recovered.state().positions().size());
		assertEquals(Map.of(), recovered.state().unsettledAttempts());
		assertEquals(null, recovered.state().lastAttemptDate());
	}

	@Test
	void rejectsADayThatIsAlreadySettledOrOlder() {
		settleSignalDayWithOneSignal();

		assertThrows(IllegalArgumentException.class, () -> ledger.settleDays("B", day(SIGNAL_DATE, Map.of())));
		assertThrows(IllegalArgumentException.class, () -> ledger.settleDays("B", day(SIGNAL_DATE.minusDays(1), Map.of())));
	}

	@Test
	void settlesSeveralDaysInDateOrderAndStopsAtTheFirstIncompleteDay() {
		settleSignalDayWithOneSignal();
		SortedMap<LocalDate, Map<String, Candle>> days = new TreeMap<>();
		days.put(DAY_3, Map.of(SYMBOL, bar(DAY_3, "10000", "10050", "9950", "10000")));
		days.put(ENTRY_DATE, flat(ENTRY_DATE));
		days.put(DAY_4, referenceOnly(DAY_4));

		PaperLedger.Report report = ledger.settleDays("B", days);

		// 정렬된 순서(9/29, 9/30, 10/1)로 처리했고, 10/1은 보유 종목의 봉이 없어 미확정이다
		assertEquals(List.of(ENTRY_DATE, DAY_3), report.settledDates());
		assertEquals(List.of(SYMBOL), report.unsettledSymbols());
		assertEquals(DAY_3, ledger.loadState("B").settledThroughDate());
		assertEquals(1, ledger.loadState("B").positions().size());
		assertEquals(2, ledger.loadState("B").positions().get(0).barsProcessed());
	}

	@Test
	void recordPendingRequiresTheSignalDayToBeSettledAndReplacesOnRerun() {
		assertThrows(IllegalStateException.class,
			() -> ledger.recordPending("B", SIGNAL_DATE, List.of(signal(SYMBOL, 1, "100", "10000"))));
		ledger.settleDays("B", day(SIGNAL_DATE, Map.of()));

		ledger.recordPending("B", SIGNAL_DATE, List.of(signal(SYMBOL, 1, "100", "10000")));
		// 같은 신호일에 다른 종목 집합으로 다시 부르면 대기 신호를 교체한다 (앞서 쓴 신호 기록 행은 그대로 남는다)
		ledger.recordPending("B", SIGNAL_DATE, List.of(signal("000660", 2, "200", "20000")));

		List<PendingSignal> pending = ledger.loadState("B").pending();
		assertEquals(1, pending.size());
		assertEquals("000660", pending.get(0).symbol());
	}

	@Test
	void recordPendingRejectsWrongStrategyWrongDateDuplicatesAndLeftovers() {
		ledger.settleDays("B", day(SIGNAL_DATE, Map.of()));

		PendingSignal otherStrategy = new PendingSignal("A", SYMBOL, 1, SIGNAL_DATE, new BigDecimal("100"), new BigDecimal("10000"));
		assertThrows(IllegalArgumentException.class, () -> ledger.recordPending("B", SIGNAL_DATE, List.of(otherStrategy)));
		PendingSignal otherDate = new PendingSignal("B", SYMBOL, 1, SIGNAL_DATE.minusDays(1), new BigDecimal("100"), new BigDecimal("10000"));
		assertThrows(IllegalArgumentException.class, () -> ledger.recordPending("B", SIGNAL_DATE, List.of(otherDate)));
		assertThrows(IllegalArgumentException.class, () -> ledger.recordPending("B", SIGNAL_DATE,
			List.of(signal(SYMBOL, 1, "100", "10000"), signal(SYMBOL, 2, "100", "10000"))));

		// 처리 안 된 이전 신호일의 대기 신호가 남은 상태는 파일이 손으로 고쳐졌을 때만 생긴다
		new PaperStateStore(dir.resolve("data")).save(new PaperStrategyState("B", ENTRY_DATE, List.of(),
			List.of(signal(SYMBOL, 1, "100", "10000")), List.of(), List.of(), Map.of(), null));
		assertThrows(IllegalStateException.class,
			() -> ledger.recordPending("B", ENTRY_DATE, List.of(new PendingSignal("B", "000660", 2, ENTRY_DATE,
				new BigDecimal("100"), new BigDecimal("10000")))));
	}

	@Test
	void strategiesAreSettledIndependently() {
		ledger.settleDays("A", day(SIGNAL_DATE, Map.of()));

		assertEquals(SIGNAL_DATE, ledger.loadState("A").settledThroughDate());
		assertEquals(null, ledger.loadState("B").settledThroughDate());
	}

	@Test
	void recordRunLogsAGapWithoutChangingState() throws IOException {
		settleSignalDayWithOneSignal();
		PaperStrategyState before = ledger.loadState("B");

		ledger.recordRun("B", "SIGNAL_GAP", DAY_3, "not_confirmed_yet");

		assertEquals(before, ledger.loadState("B"));
		assertTrue(csv("runs.csv").stream().anyMatch(l -> l.contains(",2026-09-30,SIGNAL_GAP,")), csv("runs.csv").toString());
	}

	@Test
	void aFolderUsedWithOneSlippageOrVersionRefusesAnotherSetting() {
		ledger.settleDays("B", day(SIGNAL_DATE, Map.of()));

		PaperLedger otherSlippage = ledger(new BigDecimal("0.002"), "v1");
		PaperLedger otherVersion = ledger(BigDecimal.ZERO, "v2");
		assertThrows(IllegalStateException.class, () -> otherSlippage.loadState("B"));
		assertThrows(IllegalStateException.class, () -> otherVersion.settleDays("B", day(ENTRY_DATE, Map.of())));
		// 자릿수만 다른 같은 값(0과 0.000)은 같은 설정이다
		ledger(new BigDecimal("0.000"), "v1").loadState("B");
	}

	@Test
	void ledgerCannotBeUsedAfterTheLockIsReleased() {
		ledger.settleDays("B", day(SIGNAL_DATE, Map.of()));
		lock.close();

		assertThrows(IllegalStateException.class, () -> ledger.loadState("B"));
		assertThrows(IllegalStateException.class, () -> ledger.settleDays("B", day(ENTRY_DATE, Map.of())));
		lock = PaperRunLock.tryAcquire(dir.resolve("paper.lock")).orElseThrow(); // tearDown이 닫을 수 있게 다시 쥔다
	}

	@Test
	void acceptsKoreanTimeClocksInAnyZoneRepresentationButRejectsOthers() {
		Instant now = Instant.parse("2026-09-30T16:00:00Z"); // 서울 10/1 01:00
		Path data = dir.resolve("d");
		Path records = dir.resolve("r");

		new PaperLedger(data, records, "v1", BigDecimal.ZERO, Clock.fixed(now, SEOUL), lock);
		// 같은 시간인 +09:00 오프셋 시계도 통과한다 (ZoneId 동등 비교였다면 거부됐을 것)
		new PaperLedger(data, records, "v1", BigDecimal.ZERO, Clock.fixed(now, ZoneOffset.ofHours(9)), lock);

		// UTC 시계는 이 시각에 전날(9/30) 날짜를 주므로 거부한다. 다른 오프셋도 마찬가지다
		assertThrows(IllegalArgumentException.class,
			() -> new PaperLedger(data, records, "v1", BigDecimal.ZERO, Clock.fixed(now, ZoneOffset.UTC), lock));
		assertThrows(IllegalArgumentException.class,
			() -> new PaperLedger(data, records, "v1", BigDecimal.ZERO, Clock.fixed(now, ZoneId.of("America/New_York")), lock));
		assertThrows(IllegalArgumentException.class,
			() -> new PaperLedger(data, records, "v1", BigDecimal.ZERO, Clock.fixed(now, ZoneOffset.ofHours(8)), lock));
	}

	@Test
	void rejectsNegativeSlippage() {
		assertThrows(IllegalArgumentException.class, () -> ledger(new BigDecimal("-0.001"), "v1"));
	}
}
