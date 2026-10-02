package com.damdam.bot.papertrader;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.papertrading.PaperRunLock;
import com.damdam.bot.papertrading.SignalScanService;
import com.damdam.bot.papertrading.TargetUniverse;
import com.damdam.bot.papertrading.TargetUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.KST;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.REFERENCE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.basis;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.candle;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kst;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.row;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.scan;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.scanOn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 진짜 원장(임시 폴더)과 진짜 봉 조회기를 쓰고, 네트워크에 닿는 서비스(종목군, 신호 판정, 캘린더)와 알림만 가짜로 바꿔서
// 한 번 실행의 흐름 전체를 확인한다. 슬리피지 0: 시가 10000, ATR 100이면 익절가 10100, 손절 감시가 9900이다
class PaperRunnerTest {

	private static final LocalDate MON = LocalDate.of(2026, 10, 5);
	private static final LocalDate TUE = LocalDate.of(2026, 10, 6);
	private static final String SYMBOL = "111111";
	private static final String OTHER = "222222";
	private static final Instant EVENING_RANKING = kst("2026-10-02T20:17:34");
	private static final Instant EVENING_FLOW = kst("2026-10-02T20:21:22");

	@TempDir
	Path dir;

	private TargetUniverseService universeService;
	private SignalScanService scanService;
	private MarketCalendarService calendar;
	private Notifier notifier;
	private Map<String, List<Candle>> candlesBySymbol;
	private PaperRunner.Settings settings;

	private static boolean weekday(LocalDate date) {
		return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	@BeforeEach
	void setUp() {
		universeService = mock(TargetUniverseService.class);
		scanService = mock(SignalScanService.class);
		calendar = mock(MarketCalendarService.class);
		when(calendar.isTradingDay(any())).thenAnswer(invocation -> weekday(invocation.getArgument(0)));
		notifier = mock(Notifier.class);
		candlesBySymbol = new HashMap<>();
		candlesBySymbol.put(REFERENCE, List.of(candle(SIGNAL_DATE, "50000", "51000", "49000", "50500", "9000"),
			candle(MON, "50500", "51000", "50000", "50700", "9000"), candle(TUE, "50700", "51000", "50000", "50800", "9000")));
		candlesBySymbol.put(SYMBOL, List.of(candle(MON, "10000", "10050", "9950", "10000", "1000"),
			candle(TUE, "10000", "10050", "9950", "10000", "1000")));
		settings = new PaperRunner.Settings(dir.resolve("state"), dir.resolve("records"), dir.resolve("state/paper-run.lock"),
			BigDecimal.ZERO, "t1", REFERENCE);
	}

	// 호출할 때마다 1초씩 흐르는 시계. 실행 안에서 시각이 정확히 같은 두 시점이 생기지 않게 한다
	private static final class TickingClock extends Clock {
		private Instant now;

		TickingClock(Instant start) {
			this.now = start;
		}

		@Override
		public ZoneId getZone() {
			return KST;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			Instant current = now;
			now = now.plusSeconds(1);
			return current;
		}
	}

	private PaperRunner runnerAt(String kstDateTime) {
		SettlementBarsLoader loader = new SettlementBarsLoader(symbol -> candlesBySymbol.getOrDefault(symbol, List.of()),
			PaperRunnerTest::weekday, 0);
		return new PaperRunner(universeService, scanService, loader, calendar, notifier, settings,
			new TickingClock(kst(kstDateTime)));
	}

	// 처음 가동: 원장 폴더가 비어 있으므로 --init-ledger가 필요하다
	private void firstRunAt(String kstDateTime) {
		runnerAt(kstDateTime).run(PaperRunner.INIT_LEDGER_ARG);
	}

	private void givenUniverse(Instant rankedAt) {
		when(universeService.build()).thenReturn(new TargetUniverse(
			List.of(new TargetUniverse.Member(1, SYMBOL, "이름", "1")), List.of(), rankedAt));
	}

	private JsonNode state(String strategy) throws IOException {
		return new ObjectMapper().readTree(Files.readString(dir.resolve("state/state_" + strategy + ".json")));
	}

	private List<String> lines(String file) throws IOException {
		return Files.readAllLines(dir.resolve("records").resolve(file));
	}

	// "전략@정산일" 목록. runs.csv 열: run_at, strategy, settle_date, status, ...
	private List<String> gapRows() throws IOException {
		return lines("runs.csv").stream().filter(line -> line.contains(",SIGNAL_GAP,"))
			.map(line -> line.split(",")[1] + "@" + line.split(",")[2]).toList();
	}

	private String settledThrough(String strategy) throws IOException {
		return state(strategy).get("settledThroughDate").toString().replace("\"", "");
	}

	private String lastNotification() {
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(notifier, atLeastOnce()).sendNow(captor.capture());
		return captor.getValue();
	}

	@Test
	void firstEveningSettlesTheSignalDateSavesPendingSignalsAndReportsOnce() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));

		firstRunAt("2026-10-02T21:00:00");

		assertEquals(1, state("B").get("pending").size());
		assertTrue(state("B").get("pending").toString().contains(SYMBOL));
		assertEquals(0, state("A").get("pending").size());
		assertEquals("2026-10-02", settledThrough("A"));
		assertEquals(List.of(), gapRows());
		assertEquals(2, lines("scan_rows.csv").size());   // 헤더와 한 종목
		verify(notifier, times(1)).sendNow(any());
		assertTrue(lastNotification().contains("전략 B: 정산 1거래일, 대기 신호 1건 저장"));
		assertTrue(lastNotification().contains("전략 A: 정산 1거래일, 대기 신호 0건 저장"));
	}

	@Test
	void nextEveningEntersYesterdaysPendingSignalAtTheOpenAndSavesTodaysSignals() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		firstRunAt("2026-10-02T21:00:00");

		givenUniverse(kst("2026-10-05T20:17:40"));
		when(scanService.scan(any(), eq(MON))).thenReturn(scanOn(MON, kst("2026-10-05T21:00:05"),
			row(1, SYMBOL, false, false, kst("2026-10-05T20:21:30"), basis("100", "10000"))));
		runnerAt("2026-10-05T21:00:00").run();

		// 대기 신호가 월요일 시가 10000에 진입했고, 고가 10050과 저가 9950이 익절가 10100과 손절 감시가 9900 안이라 보유 중이다
		assertEquals(0, state("B").get("pending").size());
		assertTrue(state("B").get("positions").toString().contains(SYMBOL));
		assertEquals(1, state("B").get("positions").size());
		assertEquals(List.of(), gapRows());
		assertTrue(lines("bars.csv").stream().anyMatch(line -> line.startsWith("2026-10-05," + SYMBOL + ",")));
	}

	@Test
	void aProvisionalRankingLeavesAGapForBothStrategiesAndNeverRunsTheScan() throws IOException {
		givenUniverse(kst("2026-10-02T15:40:00"));

		firstRunAt("2026-10-02T21:00:00");

		verify(scanService, never()).scan(any(), any());
		assertEquals(List.of("A@2026-10-02", "B@2026-10-02"), gapRows());
		assertEquals(0, state("B").get("pending").size());
		assertEquals("2026-10-02", settledThrough("B"));   // 정산은 했다
		assertTrue(lastNotification().startsWith("[주의] "));
		assertTrue(lastNotification().contains("신호 공백: 순위 집계 시각"));
	}

	@Test
	void aProvisionalFlowRecordOnASignaledRowGapsOnlyStrategyA() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, false, true, kst("2026-10-02T15:40:00"), basis("100", "10000")),
			row(2, OTHER, true, false, EVENING_FLOW, basis("200", "20000"))));

		firstRunAt("2026-10-02T21:00:00");

		assertEquals(List.of("A@2026-10-02"), gapRows());
		assertEquals(0, state("A").get("pending").size());
		assertEquals(1, state("B").get("pending").size());
	}

	@Test
	void aScanFailureIsRecordedAsAGapReportedAndRethrown() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), any())).thenThrow(new IllegalStateException("전략별 판정 종목이 기준 미만입니다"));

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> firstRunAt("2026-10-02T21:00:00"));

		assertTrue(thrown.getMessage().contains("판정 종목이 기준 미만"));
		assertEquals(List.of("A@2026-10-02", "B@2026-10-02"), gapRows());
		// 알림은 두 건이다: 공백 사유를 담은 실행 보고, 그다음 실패 알림 (비정상 종료 직전)
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(notifier, times(2)).sendNow(captor.capture());
		assertTrue(captor.getAllValues().get(0).contains("신호 공백: 신호 판정 실패"));
		assertTrue(captor.getAllValues().get(1).startsWith("[가상매매 실패]"));
	}

	@Test
	void aRunWhileAnotherHoldsTheLockFailsAndSaysSo() {
		try (PaperRunLock held = PaperRunLock.tryAcquire(settings.lockFile()).orElseThrow()) {
			assertTrue(held.isHeld());

			IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> firstRunAt("2026-10-02T21:00:00"));

			assertTrue(thrown.getMessage().contains("잠금"));
		}
		verify(universeService, never()).build();
		assertTrue(lastNotification().startsWith("[가상매매 실패]"));
	}

	@Test
	void aSignalDateBeforeTheConfirmedTimeUsesThePreviousTradingDay() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-03T02:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));

		// 토요일 새벽에 돌아도 신호일은 금요일이고 금요일 저녁 순위로 판정한다
		firstRunAt("2026-10-03T02:00:00");

		assertEquals(1, state("B").get("pending").size());
		verify(scanService).scan(any(), eq(SIGNAL_DATE));
	}

	// --- 독립 검토(2026-10-03)가 짚은 시나리오 ---

	@Test
	void aRerunDoesNotRescanOrOverwriteSignalsAlreadySavedForTheSignalDate() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		firstRunAt("2026-10-02T21:00:00");

		// 재실행에서는 조회 장애로 덜 판정된 다른 결과가 나올 수 있다. 그 결과가 쓰이면 안 된다
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T22:00:05")));
		runnerAt("2026-10-02T22:00:00").run();

		verify(scanService, times(1)).scan(any(), any());
		verify(universeService, times(1)).build();
		assertEquals(1, state("B").get("pending").size());
		assertTrue(state("B").get("pending").toString().contains(SYMBOL));
		assertEquals(2, lines("scan_rows.csv").size());   // 재실행의 판정 행은 쌓이지 않는다
		assertEquals(List.of(), gapRows());
		assertTrue(lastNotification().contains("전략 B: 정산 0거래일, 이미 저장된 대기 신호 1건이 있어 다시 판정하지 않음"));
		assertFalse(lastNotification().startsWith("[주의]"));
	}

	@Test
	void aRerunThatWouldHaveHitAClosedGateLeavesNoFalseGapWhenSignalsWereAlreadySaved() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		firstRunAt("2026-10-02T21:00:00");

		// 월요일 아침 7시에 재실행: 신호일은 여전히 금요일이고, 순위가 06:50에 갱신됐다면 게이트가 닫힐 상황이다
		givenUniverse(kst("2026-10-05T06:51:00"));
		runnerAt("2026-10-05T07:00:00").run();

		assertEquals(List.of(), gapRows());
		assertEquals(1, state("B").get("pending").size());
	}

	@Test
	void aRerunAfterAGapCanStillSaveTheSignalsAndMarksThemSaved() throws IOException {
		givenUniverse(kst("2026-10-02T15:40:00"));   // 첫 실행: 잠정 순위라 공백
		firstRunAt("2026-10-02T21:00:00");
		assertEquals(List.of("A@2026-10-02", "B@2026-10-02"), gapRows());

		givenUniverse(EVENING_RANKING);               // 재실행: 이번에는 저녁 순위
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T22:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		runnerAt("2026-10-02T22:00:00").run();

		assertEquals(1, state("B").get("pending").size());   // 공백이 났던 날도 재실행으로 신호가 저장된다
		assertEquals(2, lines("runs.csv").stream().filter(line -> line.contains(",SIGNALS_SAVED,")).count());   // A와 B 표시
		// 세 번째 실행은 이제 다시 판정하지 않는다
		runnerAt("2026-10-02T23:00:00").run();
		verify(scanService, times(1)).scan(any(), any());
	}

	@Test
	void aMissedTradingDayIsRecordedAsAGapForBothStrategiesWhenCatchingUp() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		firstRunAt("2026-10-02T21:00:00");

		// 월요일 저녁에는 PC가 꺼져 있었고 화요일 저녁에 돌렸다: 월요일은 소급 정산하되 그날 신호는 만들 수 없다
		givenUniverse(kst("2026-10-06T20:17:40"));
		when(scanService.scan(any(), eq(TUE))).thenReturn(scanOn(TUE, kst("2026-10-06T21:00:05"),
			row(1, SYMBOL, false, false, kst("2026-10-06T20:21:30"), basis("100", "10000"))));
		runnerAt("2026-10-06T21:00:00").run();

		assertEquals(List.of("A@2026-10-05", "B@2026-10-05"), gapRows());
		assertEquals("2026-10-06", settledThrough("B"));
		assertTrue(state("B").get("positions").toString().contains(SYMBOL));   // 월요일 시가 진입이 소급 정산됐다
		assertTrue(lines("bars.csv").stream().anyMatch(line -> line.startsWith("2026-10-05," + SYMBOL + ",")));
		assertTrue(lines("bars.csv").stream().anyMatch(line -> line.startsWith("2026-10-06," + SYMBOL + ",")));
	}

	@Test
	void oneStrategyBlockedByAMissingBarDoesNotStopTheOther() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, OTHER, false, true, EVENING_FLOW, basis("100", "10000")),     // 전략 A 신호, 이 종목은 월요일 봉이 없다
			row(2, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));   // 전략 B 신호
		firstRunAt("2026-10-02T21:00:00");

		givenUniverse(kst("2026-10-05T20:17:40"));
		when(scanService.scan(any(), eq(MON))).thenReturn(scanOn(MON, kst("2026-10-05T21:00:05"),
			row(1, SYMBOL, false, false, kst("2026-10-05T20:21:30"), basis("100", "10000"))));
		runnerAt("2026-10-05T21:00:00").run();

		assertEquals(List.of("A@2026-10-05"), gapRows());
		assertEquals("2026-10-02", settledThrough("A"));   // A는 봉이 없는 종목 때문에 정산이 막혔다
		assertEquals("2026-10-05", settledThrough("B"));
		assertTrue(lastNotification().startsWith("[주의] "));
		assertTrue(lastNotification().contains("미확정 종목 " + OTHER));
	}

	@Test
	void anExceptionAfterOneStrategyWasSavedLeavesAGapRowForTheOtherAndRethrows() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		// 전략 B의 신호 기록 파일에 같은 키(전략, 종목, 신호일)의 다른 내용이 미리 있으면 원장이 예외를 던진다 (CSV는 조용히 덮지 않는다)
		Files.createDirectories(dir.resolve("records"));
		Files.writeString(dir.resolve("records/signals_B.csv"),
			"strategy,symbol,signal_date,rank,atr,signal_close\nB," + SYMBOL + ",2026-10-02,1,999,10000\n");

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> firstRunAt("2026-10-02T21:00:00"));

		assertTrue(thrown.getMessage().contains("같은 키"));
		assertEquals(List.of("B@2026-10-02"), gapRows());   // A는 저장을 마쳤고 B만 결과가 없어 공백이 남았다
		assertEquals(0, state("A").get("pending").size());
		assertTrue(lines("runs.csv").stream().anyMatch(line -> line.contains(",SIGNAL_GAP,") && line.contains("실행 중 오류")));
	}

	@Test
	void aScanRowLogFailureStillSavesSignalsAndIsReportedAsAWarning() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05"),
			row(1, SYMBOL, true, false, EVENING_FLOW, basis("100", "10000"))));
		// 기록 파일을 쓸 수 없는 상황(다른 프로그램이 파일을 잡고 있는 경우 등)을 폴더로 흉내 낸다
		Files.createDirectories(dir.resolve("records/scan_rows.csv"));

		firstRunAt("2026-10-02T21:00:00");

		assertEquals(1, state("B").get("pending").size());   // 신호 저장은 막히지 않았다
		assertEquals(List.of(), gapRows());
		assertTrue(lastNotification().startsWith("[주의] "));
		assertTrue(lastNotification().contains("경고: scan_rows.csv 기록에 실패했습니다"));
	}

	@Test
	void anEmptyLedgerFolderIsNotOpenedWithoutTheInitFlag() {
		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> runnerAt("2026-10-02T21:00:00").run());

		assertTrue(thrown.getMessage().contains("원장 설정 파일이 없습니다"));
		assertTrue(thrown.getMessage().contains(PaperRunner.INIT_LEDGER_ARG));
		assertFalse(Files.exists(dir.resolve("state/state_A.json")));
		assertFalse(Files.exists(dir.resolve("state/ledger_config.json")));   // 조용히 새 원장을 만들지 않았다
		verify(universeService, never()).build();
		assertTrue(lastNotification().startsWith("[가상매매 실패]"));
	}

	@Test
	void afterTheFirstRunTheFlagIsNoLongerNeeded() throws IOException {
		givenUniverse(EVENING_RANKING);
		when(scanService.scan(any(), eq(SIGNAL_DATE))).thenReturn(scan(kst("2026-10-02T21:00:05")));
		firstRunAt("2026-10-02T21:00:00");

		runnerAt("2026-10-02T21:30:00").run();   // 플래그 없이도 열린다

		assertTrue(Files.exists(dir.resolve("state/ledger_config.json")));
	}
}
