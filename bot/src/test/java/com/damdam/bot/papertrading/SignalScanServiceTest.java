package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.SignalInputService;
import com.damdam.bot.stocks.StockLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignalScanServiceTest {

	private static final LocalDate SIGNAL_DATE = LocalDate.parse("2026-09-28");
	private static final Instant SCANNED_AT = Instant.parse("2026-09-28T11:30:00Z");
	private static final Instant FLOW_UPDATED_AT = Instant.parse("2026-09-28T11:05:00Z");

	private SignalInputService inputService;
	private SignalScanService service;

	@BeforeEach
	void setUp() {
		inputService = mock(SignalInputService.class);
		service = new SignalScanService(inputService, 0, Clock.fixed(SCANNED_AT, ZoneOffset.UTC));
	}

	private static Candle candle(LocalDate date, String high, String close, String volume) {
		String timestamp = date.atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toString();
		return new Candle(timestamp, "100", high, "100", close, volume, "KRW");
	}

	// 신호일부터 하루씩 과거로 가는 최신순 일봉. 가격이 모두 같아 구름을 돌파하지 않고, 거래량은 봉마다 1000이다
	private static List<Candle> flatCandles(LocalDate latest, int count) {
		List<Candle> candles = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			candles.add(candle(latest.minusDays(i), "100", "100", "1000"));
		}
		return candles;
	}

	// 위 평평한 봉의 0번만 종가 110, 거래량 2000으로 바꾼다: 어제까지 구름 상단(100) 이하였다가 구름 위로 마감하고 거래량이 평균의 정확히 2배
	private static List<Candle> breakoutCandles(LocalDate latest, int count) {
		List<Candle> candles = flatCandles(latest, count);
		candles.set(0, candle(latest, "110", "110", "2000"));
		return candles;
	}

	// 신호일부터 3거래일(위 봉들의 날짜)에 같은 기관 순매수량을 넣은 기록
	private static List<InvestorTradingRecord> flows(String netBuyEachDay) {
		List<InvestorTradingRecord> records = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			records.add(new InvestorTradingRecord(SIGNAL_DATE.minusDays(i), new BigDecimal(netBuyEachDay), FLOW_UPDATED_AT));
		}
		return records;
	}

	private static TargetUniverse universe(String... symbols) {
		List<TargetUniverse.Member> members = new ArrayList<>();
		for (int i = 0; i < symbols.length; i++) {
			members.add(new TargetUniverse.Member(i + 1, symbols[i], "이름" + symbols[i], "1000000"));
		}
		return new TargetUniverse(members, List.of(), null);
	}

	private void givenGoodInputs(String symbol, String netBuyEachDay) {
		when(inputService.getDailyCandles(symbol)).thenReturn(flatCandles(SIGNAL_DATE, 100));
		when(inputService.getInstitutionFlows(symbol)).thenReturn(flows(netBuyEachDay));
	}

	private void givenCandleFailure(String symbol) {
		when(inputService.getDailyCandles(symbol)).thenThrow(new StockLookupException(symbol, "일봉 조회", "HTTP 500"));
	}

	private void givenFlowFailure(String symbol) {
		when(inputService.getDailyCandles(symbol)).thenReturn(flatCandles(SIGNAL_DATE, 100));
		when(inputService.getInstitutionFlows(symbol)).thenThrow(new StockLookupException(symbol, "매매동향 조회", "HTTP 500"));
	}

	@Test
	void evaluatesBothStrategiesPerSymbolAndKeepsRankOrder() {
		givenGoodInputs("A", "40");   // 3일 합계 120 / 거래량 합계 3000 = 4% 이상이고 연속 순매수 -> 전략 A 신호
		givenGoodInputs("B", "10");   // 합계 30 / 3000 = 1% -> 신호 아님

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		assertEquals(SIGNAL_DATE, scan.signalDate());
		assertEquals(SCANNED_AT, scan.scannedAt());
		assertEquals(List.of("A", "B"), scan.rows().stream().map(SignalScan.Row::symbol).toList());
		assertEquals(List.of(1, 2), scan.rows().stream().map(SignalScan.Row::rank).toList());
		assertEquals("이름A", scan.rows().get(0).name());

		SignalScan.Row a = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.EVALUATED, a.institution().status());
		assertEquals(StrategyOutcome.Status.EVALUATED, a.ichimoku().status());
		assertTrue(a.institutionSignaled());
		assertFalse(a.ichimokuSignaled()); // 가격이 평평해 구름을 돌파하지 않는다
		assertEquals(FLOW_UPDATED_AT, a.institutionRecordUpdatedAt());

		SignalScan.Row b = scan.rows().get(1);
		assertFalse(b.institutionSignaled());
		assertEquals(new BigDecimal("30"), b.institution().result().netBuySum());
	}

	@Test
	void ichimokuSignalIsPassedThroughWhenThePriceBreaksOutWithVolume() {
		when(inputService.getDailyCandles("A")).thenReturn(breakoutCandles(SIGNAL_DATE, 100));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("10"));
		givenGoodInputs("B", "10");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		assertTrue(scan.rows().get(0).ichimokuSignaled());
		assertEquals(SIGNAL_DATE, scan.rows().get(0).ichimoku().result().signalDate());
		assertFalse(scan.rows().get(1).ichimokuSignaled());
	}

	@Test
	void entryBasisHoldsSignalDayAtrAndCloseFromTheSameCandles() {
		when(inputService.getDailyCandles("A")).thenReturn(breakoutCandles(SIGNAL_DATE, 100));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("10"));

		SignalScan.EntryBasis basis = service.scan(universe("A"), SIGNAL_DATE).rows().get(0).entryBasis();

		// 신호일 봉의 진짜 변동폭은 고가 110 - 저가 100 = 10이고 나머지 13일은 0이라 ATR(14) = 10 / 14
		assertEquals(new BigDecimal("0.7143"), basis.atr());
		assertEquals(new BigDecimal("110"), basis.signalClose());
	}

	@Test
	void entryBasisIgnoresCandlesNewerThanTheSignalDate() {
		List<Candle> candles = new ArrayList<>(breakoutCandles(SIGNAL_DATE, 100));
		candles.add(0, candle(SIGNAL_DATE.plusDays(1), "500", "500", "9"));   // 다음 거래일의 잠정 봉
		when(inputService.getDailyCandles("A")).thenReturn(candles);
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("10"));

		SignalScan.EntryBasis basis = service.scan(universe("A"), SIGNAL_DATE).rows().get(0).entryBasis();

		assertEquals(new BigDecimal("110"), basis.signalClose());
		assertEquals(new BigDecimal("0.7143"), basis.atr());
	}

	@Test
	void entryBasisIsNullWhenThereAreTooFewCandlesForAtrOrTheFetchFailed() {
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE, 14));   // ATR(14)은 15봉 필요
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("10"));
		givenCandleFailure("B");
		// 판정 종목이 전략마다 절반 이상이어야 스캔이 성공하므로 정상 종목을 충분히 둔다
		givenGoodInputs("C", "10");
		givenGoodInputs("D", "10");
		givenGoodInputs("E", "10");

		SignalScan scan = service.scan(universe("A", "B", "C", "D", "E"), SIGNAL_DATE);

		assertNull(scan.rows().get(0).entryBasis());
		assertNull(scan.rows().get(1).entryBasis());
		assertEquals(new BigDecimal("0.0000"), scan.rows().get(2).entryBasis().atr());
	}

	@Test
	void tooFewCandlesSkipsOnlyTheStrategyThatNeedsMoreOfThem() {
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE, 5)); // B는 78봉 필요, A는 3봉이면 됨
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		givenGoodInputs("B", "10");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		SignalScan.Row row = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.INSUFFICIENT_CANDLES, row.ichimoku().status());
		assertFalse(row.ichimokuSignaled());
		assertEquals(StrategyOutcome.Status.EVALUATED, row.institution().status());
		assertTrue(row.institutionSignaled());
		assertEquals(1, scan.ichimokuCount(StrategyOutcome.Status.INSUFFICIENT_CANDLES));
	}

	@Test
	void candleFetchFailureMarksBothStrategiesAndSkipsFlowLookup() {
		givenCandleFailure("A");
		givenGoodInputs("B", "40");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		SignalScan.Row a = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.FETCH_FAILED, a.ichimoku().status());
		assertEquals(StrategyOutcome.Status.FETCH_FAILED, a.institution().status());
		assertFalse(a.ichimokuSignaled());
		assertFalse(a.institutionSignaled());
		assertNull(a.institutionRecordUpdatedAt());
		verify(inputService, never()).getInstitutionFlows("A");
		assertEquals(StrategyOutcome.Status.EVALUATED, scan.rows().get(1).institution().status());
	}

	@Test
	void flowFetchFailureAffectsOnlyStrategyA() {
		givenFlowFailure("A");
		givenGoodInputs("B", "10");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		SignalScan.Row row = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.EVALUATED, row.ichimoku().status());
		assertEquals(StrategyOutcome.Status.FETCH_FAILED, row.institution().status());
		assertFalse(row.institutionSignaled());
	}

	@Test
	void staleCandlesAreADataErrorNotASilentNoSignal() {
		// 신호일 봉이 빠진 낡은 목록: 하루 전이 0번 봉이면 두 전략 모두 날짜 검증에서 걸린다
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE.minusDays(1), 100));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		givenGoodInputs("B", "40");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		SignalScan.Row a = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.DATA_ERROR, a.ichimoku().status());
		assertEquals(StrategyOutcome.Status.DATA_ERROR, a.institution().status());
		assertTrue(a.ichimoku().detail().contains("신호일"), a.ichimoku().detail());
	}

	@Test
	void candlesNewerThanTheSignalDateAreDroppedBeforeEvaluating() {
		// 다음 거래일 장 시작 뒤에 다시 돌리면 그날의 잠정 봉이 0번에 섞여 온다. 신호일 이후 봉은 버리고 신호일 기준으로 판정한다
		List<Candle> withNewerBar = new ArrayList<>();
		withNewerBar.add(candle(SIGNAL_DATE.plusDays(1), "100", "100", "10"));
		withNewerBar.addAll(flatCandles(SIGNAL_DATE, 100));
		when(inputService.getDailyCandles("A")).thenReturn(withNewerBar);
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		givenGoodInputs("B", "10");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		SignalScan.Row a = scan.rows().get(0);
		assertEquals(StrategyOutcome.Status.EVALUATED, a.ichimoku().status());
		assertEquals(StrategyOutcome.Status.EVALUATED, a.institution().status());
		assertEquals(SIGNAL_DATE, a.ichimoku().result().signalDate());
		assertEquals(new BigDecimal("3000"), a.institution().result().volumeSum()); // 잠정 봉의 거래량 10이 섞이지 않았다
	}

	@Test
	void everyCandleAfterTheSignalDateIsADataErrorNotAnInsufficientCandlesHint() {
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE.plusDays(10), 5).subList(0, 3));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		givenGoodInputs("B", "40");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		assertEquals(StrategyOutcome.Status.DATA_ERROR, scan.rows().get(0).ichimoku().status());
		assertEquals(StrategyOutcome.Status.DATA_ERROR, scan.rows().get(0).institution().status());
	}

	@Test
	void missingFlowDayAndDuplicateFlowDayAreDataErrorsForStrategyAOnly() {
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE, 100));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40").subList(0, 2)); // 3거래일 중 하루가 없음
		when(inputService.getDailyCandles("B")).thenReturn(flatCandles(SIGNAL_DATE, 100));
		List<InvestorTradingRecord> duplicated = new ArrayList<>(flows("40"));
		duplicated.add(new InvestorTradingRecord(SIGNAL_DATE, new BigDecimal("1"), FLOW_UPDATED_AT));
		when(inputService.getInstitutionFlows("B")).thenReturn(duplicated);
		givenGoodInputs("C", "10");
		givenGoodInputs("D", "10");

		SignalScan scan = service.scan(universe("A", "B", "C", "D"), SIGNAL_DATE);

		for (SignalScan.Row row : scan.rows().subList(0, 2)) {
			assertEquals(StrategyOutcome.Status.EVALUATED, row.ichimoku().status(), row.symbol());
			assertEquals(StrategyOutcome.Status.DATA_ERROR, row.institution().status(), row.symbol());
			assertFalse(row.institutionSignaled());
		}
	}

	@Test
	void malformedOldCandleBreaksOnlyStrategyB() {
		List<Candle> broken = new ArrayList<>(flatCandles(SIGNAL_DATE, 100));
		broken.set(3, new Candle("not-a-date", "100", "100", "100", "100", "1000", "KRW")); // 전략 A는 최근 3개 봉만 본다
		when(inputService.getDailyCandles("A")).thenReturn(broken);
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		givenGoodInputs("B", "40");

		SignalScan scan = service.scan(universe("A", "B"), SIGNAL_DATE);

		assertEquals(StrategyOutcome.Status.DATA_ERROR, scan.rows().get(0).ichimoku().status());
		assertEquals(StrategyOutcome.Status.EVALUATED, scan.rows().get(0).institution().status());
		assertEquals(StrategyOutcome.Status.EVALUATED, scan.rows().get(1).ichimoku().status());
	}

	@Test
	void totalLookupOutageFailsInsteadOfReturningAScanWithNoSignals() {
		givenCandleFailure("A");
		givenCandleFailure("B");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.scan(universe("A", "B"), SIGNAL_DATE));
		assertTrue(e.getMessage().contains("조회 실패 2"), e.getMessage());
	}

	@Test
	void outageOfOnlyStrategyAFailsEvenThoughStrategyBIsHealthy() {
		// 전략 A 조회만 전부 실패하면 전략 A는 신호 0건이 아니라 판정 불가다. 전략 B만 정상이어도 조용히 통과시키지 않는다
		for (String symbol : List.of("A", "B", "C", "D")) {
			givenFlowFailure(symbol);
		}

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.scan(universe("A", "B", "C", "D"), SIGNAL_DATE));
		assertTrue(e.getMessage().contains("전략 A 판정 0"), e.getMessage());
		assertTrue(e.getMessage().contains("전략 B 판정 4"), e.getMessage());
	}

	@Test
	void strategyBGoingBlindBecauseAllCandleListsAreShortFails() {
		// API가 100봉보다 적게 주면 전략 B 전체가 봉 부족으로 조용히 죽는다. 봉 부족만 있어도 커버리지 미달이면 알린다
		for (String symbol : List.of("A", "B", "C")) {
			when(inputService.getDailyCandles(symbol)).thenReturn(flatCandles(SIGNAL_DATE, 30));
			when(inputService.getInstitutionFlows(symbol)).thenReturn(flows("10"));
		}

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.scan(universe("A", "B", "C"), SIGNAL_DATE));
		assertTrue(e.getMessage().contains("봉 부족 3"), e.getMessage());
	}

	@Test
	void coverageExactlyAtTheFloorPassesAndOneBelowFails() {
		givenFlowFailure("A");
		givenFlowFailure("B");
		givenGoodInputs("C", "10");
		givenGoodInputs("D", "10");
		// 전략 A 판정 2/4 = 정확히 50% -> 통과
		service.scan(universe("A", "B", "C", "D"), SIGNAL_DATE);

		givenFlowFailure("C");
		// 전략 A 판정 1/4 = 25% -> 실패
		assertThrows(IllegalStateException.class, () -> service.scan(universe("A", "B", "C", "D"), SIGNAL_DATE));
	}

	@Test
	void everythingBeingADataErrorAlsoFails() {
		when(inputService.getDailyCandles("A")).thenReturn(flatCandles(SIGNAL_DATE.minusDays(1), 100));
		when(inputService.getInstitutionFlows("A")).thenReturn(flows("40"));
		assertThrows(IllegalStateException.class, () -> service.scan(universe("A"), SIGNAL_DATE));
	}

	@Test
	void emptyUniverseFails() {
		assertThrows(IllegalStateException.class, () -> service.scan(new TargetUniverse(List.of(), List.of(), null), SIGNAL_DATE));
	}

	@Test
	void interruptStopsTheScanWithoutCallingRemainingSymbols() {
		givenGoodInputs("A", "40");
		givenGoodInputs("B", "40");
		Thread.currentThread().interrupt();
		try {
			assertThrows(IllegalStateException.class, () -> service.scan(universe("A", "B"), SIGNAL_DATE));
			verify(inputService, never()).getDailyCandles("A");
			verify(inputService, never()).getDailyCandles("B");
		} finally {
			Thread.interrupted(); // 다른 테스트에 인터럽트 플래그가 남지 않게 지운다
		}
	}

	@Test
	void fetchedCandleCountCoversWhatStrategyBNeeds() {
		assertTrue(SignalInputService.CANDLE_COUNT >= IchimokuCloudBreakout.MIN_CANDLES);
		assertTrue(SignalInputService.FLOW_COUNT >= InstitutionNetBuySignal.CONSECUTIVE_DAYS);
	}
}
