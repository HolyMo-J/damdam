package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingPage;
import com.damdam.bot.ranking.RankingPrice;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.SignalInputService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import com.damdam.bot.stocks.StockLookupException;
import com.damdam.bot.stocks.StockWarning;
import com.damdam.bot.stocks.StockWarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperProbeRunnerTest {

	@TempDir
	Path tempDir;

	private RankingService rankingService;
	private StockInfoService stockInfoService;
	private StockWarningService stockWarningService;
	private SignalInputService signalInputService;
	private Path report;
	private PaperProbeRunner runner;

	@BeforeEach
	void setUp() {
		rankingService = mock(RankingService.class);
		stockInfoService = mock(StockInfoService.class);
		stockWarningService = mock(StockWarningService.class);
		signalInputService = mock(SignalInputService.class);
		report = tempDir.resolve("probe").resolve("paper-probe.log");
		runner = new PaperProbeRunner(rankingService, stockInfoService, stockWarningService, signalInputService, report, 0);
	}

	private static Ranking ranking(int rank, String symbol) {
		return new Ranking(rank, symbol, "KRW", new RankingPrice("1", "1", "0.01"), "1", "5000");
	}

	private static RankingPage page(Ranking... rankings) {
		return new RankingPage("2026-09-29T15:35:00+09:00", List.of(rankings));
	}

	private static Candle candle(String date) {
		return new Candle(date + "T00:00+09:00", "100", "110", "90", "105", "1000", "KRW");
	}

	private static InvestorTradingRecord flow(String date) {
		return new InvestorTradingRecord(LocalDate.parse(date), new BigDecimal("5"), Instant.parse("2026-09-28T11:05:00Z"));
	}

	private String reportText() throws IOException {
		return Files.readString(report);
	}

	@Test
	void reportsTheRankingDifferenceWithWarningsAndProbesTheTopTwoSymbolsByDefault() throws Exception {
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(page(ranking(1, "A"), ranking(2, "B"), ranking(3, "C")));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(page(ranking(1, "A"), ranking(2, "C"), ranking(3, "D")));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(
			new StockInfo("B", "비종목", "KOSPI", "STOCK", true, "ACTIVE", null)));
		when(stockWarningService.getWarnings("B")).thenReturn(List.of(new StockWarning("INVESTMENT_WARNING", "KRX", "2026-09-01", null)));
		for (String symbol : List.of("A", "C")) {
			when(signalInputService.getDailyCandles(symbol)).thenReturn(List.of(candle("2026-09-28"), candle("2026-09-25")));
			when(signalInputService.getInstitutionFlows(symbol)).thenReturn(List.of(flow("2026-09-28"), flow("2026-09-25")));
		}

		runner.run();

		String text = reportText();
		assertTrue(text.contains("excludeInvestmentCaution=false: rankedAt(KST)=2026-09-29 15:35:00, 항목 3개"), text);
		assertTrue(text.contains("excludeInvestmentCaution=true : rankedAt(KST)=2026-09-29 15:35:00, 항목 3개"), text);
		assertTrue(text.contains("옵션이 뺀 종목(끈 결과에만 있음): 1개"), text);
		assertTrue(text.contains("2위 B 비종목 KOSPI STOCK | 유의사항: INVESTMENT_WARNING(2026-09-01~null)"), text);
		assertTrue(text.contains("옵션을 켜서 새로 채워진 종목(켠 결과에만 있음): 1개 [D]"), text);
		// 표본은 옵션을 켠 순위의 1, 2위(A, C)
		assertTrue(text.contains("[일봉] A: 요청 100봉, 받은 봉 2개"), text);
		assertTrue(text.contains("[일봉] C: 요청 100봉, 받은 봉 2개"), text);
		assertTrue(text.contains("0번 봉 timestamp=2026-09-28T00:00+09:00 종가=105 거래량=1000"), text);
		assertTrue(text.contains("0번 기록 date=2026-09-28 updatedAt(KST)=2026-09-28 20:05:00 기관순매수=5"), text);
		assertTrue(text.contains("최신 기록 날짜 2026-09-28 vs 일봉 0번 날짜 2026-09-28 -> 같음"), text);
		assertFalse(text.contains("[일봉] D"), text);
	}

	@Test
	void explicitSymbolsAreUsedInsteadOfTheRankingSample() throws Exception {
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(page(ranking(1, "A")));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(page(ranking(1, "A")));
		when(signalInputService.getDailyCandles("X")).thenReturn(List.of(candle("2026-09-28")));
		when(signalInputService.getInstitutionFlows("X")).thenReturn(List.of(flow("2026-09-25")));

		runner.run("--spring.profiles.active=paper-probe", "X");

		String text = reportText();
		assertTrue(text.contains("[일봉] X:"), text);
		assertTrue(text.contains("순위 목록(옵션 켠 결과)에 없는 종목이라 순위 항목과의 대조는 생략합니다"), text);
		assertTrue(text.contains("최신 기록 날짜 2026-09-25 vs 일봉 0번 날짜 2026-09-28 -> 다름"), text);
		verify(signalInputService, never()).getDailyCandles("A");
	}

	@Test
	void aFailingSectionDoesNotStopTheOthersAndTheFailureIsRecorded() throws Exception {
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenThrow(new IllegalStateException("순위 장애"));
		when(signalInputService.getDailyCandles("X")).thenThrow(new StockLookupException("X", "일봉 조회", "HTTP 500"));
		when(signalInputService.getInstitutionFlows("X")).thenReturn(List.of(flow("2026-09-28")));

		runner.run("X");

		String text = reportText();
		assertTrue(text.contains("[순위] 조회 실패: IllegalStateException 순위 장애"), text);
		assertTrue(text.contains("[일봉] X 조회 실패: StockLookupException"), text);
		assertTrue(text.contains("[매매동향] X: 요청 10건, 받은 기록 1건"), text);
		assertFalse(text.contains("최신 기록 날짜"), text); // 일봉을 못 받았으니 날짜 비교는 하지 않는다
	}

	@Test
	void warningCensusCountsEveryTypeAcrossTheWholeRankingAndNamesTheNotableOnes() throws Exception {
		RankingPage same = page(ranking(1, "A"), ranking(2, "B"), ranking(3, "C"));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(same);
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(same);
		when(stockWarningService.getWarnings("A")).thenReturn(List.of(new StockWarning("OVERHEATED", "KRX", "2026-09-01", null)));
		when(stockWarningService.getWarnings("B")).thenReturn(List.of(
			new StockWarning("INVESTMENT_WARNING", "KRX", "2026-09-01", null), new StockWarning("VI_STATIC", "KRX", null, null)));
		when(stockWarningService.getWarnings("C")).thenThrow(new StockLookupException("C", "경고 조회", "HTTP 500"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(
			new StockInfo("A", "이름A", "KOSPI", "STOCK", true, "ACTIVE", null),
			new StockInfo("B", "이름B", "KOSPI", "STOCK", true, "ACTIVE", null)));

		runner.run("X");

		String text = reportText();
		assertTrue(text.contains("[유의사항 집계] 옵션을 켠 순위 3개 중 유의사항이 있는 종목 2개, 조회 실패 1개"), text);
		assertTrue(text.contains("INVESTMENT_WARNING 1개 [B]"), text);
		assertTrue(text.contains("OVERHEATED 1개 [A]"), text);
		assertTrue(text.contains("VI_STATIC 1개"), text);
		assertFalse(text.contains("VI_STATIC 1개 ["), text); // VI는 종목 목록 없이 개수만
		assertTrue(text.contains("종목 이름: A 이름A, B 이름B"), text);
	}

	@Test
	void noWarningsAnywhereIsReportedExplicitly() throws Exception {
		RankingPage same = page(ranking(1, "A"));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(same);
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(same);

		runner.run("X");

		assertTrue(reportText().contains("유의사항이 있는 종목이 없습니다"), reportText());
	}

	@Test
	void rankingEntryIsComparedWithTheLatestCandleForPriceChangeRateAndVolume() throws Exception {
		Ranking entry = new Ranking(1, "A", "KRW", new RankingPrice("105", "100", "0.05"), "2000", "5000");
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(page(entry));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(page(entry));
		when(signalInputService.getDailyCandles("A")).thenReturn(List.of(
			new Candle("2026-09-28T00:00+09:00", "100", "110", "90", "105", "1000", "KRW"),
			new Candle("2026-09-25T00:00+09:00", "100", "110", "90", "100", "900", "KRW")));
		when(signalInputService.getInstitutionFlows("A")).thenReturn(List.of(flow("2026-09-28")));

		runner.run();

		String text = reportText();
		assertTrue(text.contains("순위 항목: 1위 lastPrice=105 basePrice=100 changeRate=0.05 tradingVolume=2000 tradingAmount=5000"), text);
		assertTrue(text.contains("현재가 대조: 순위 lastPrice=105 vs 일봉 0번 종가=105 -> 같음"), text);
		assertTrue(text.contains("등락률 대조: 순위 changeRate=0.05 vs 일봉 종가 기준 0.0500"), text);
		assertTrue(text.contains("거래량 대조: 일봉 0번 1000 / 순위 2000 = 0.5000"), text);
	}

	@Test
	void differingPriceIsFlaggedAsDifferent() throws Exception {
		Ranking entry = new Ranking(1, "A", "KRW", new RankingPrice("106", "100", "0.06"), "1000", "5000");
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(page(entry));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(page(entry));
		when(signalInputService.getDailyCandles("A")).thenReturn(List.of(
			new Candle("2026-09-28T00:00+09:00", "100", "110", "90", "105", "1000", "KRW"),
			new Candle("2026-09-25T00:00+09:00", "100", "110", "90", "100", "900", "KRW")));
		when(signalInputService.getInstitutionFlows("A")).thenReturn(List.of(flow("2026-09-28")));

		runner.run();

		assertTrue(reportText().contains("순위 lastPrice=106 vs 일봉 0번 종가=105 -> 다름"), reportText());
		assertTrue(reportText().contains("= 1.0000"), reportText());
	}

	private PaperProbeRunner runnerWithWaiter(List<Long> waits) {
		return new PaperProbeRunner(rankingService, stockInfoService, stockWarningService, signalInputService, report, 0, waits::add);
	}

	private void givenSteadyInputs(String symbol) throws Exception {
		when(signalInputService.getDailyCandles(symbol)).thenReturn(List.of(candle("2026-09-28"), candle("2026-09-25")));
		when(signalInputService.getInstitutionFlows(symbol)).thenReturn(List.of(flow("2026-09-28")));
	}

	@Test
	void repeatModeRunsTheRequestedRoundsWaitsBetweenThemAndDoesTheCensusOnlyOnce() throws Exception {
		RankingPage same = page(ranking(1, "A"), ranking(2, "B"));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(same);
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(same);
		givenSteadyInputs("A");
		givenSteadyInputs("B");
		List<Long> waits = new java.util.ArrayList<>();

		runnerWithWaiter(waits).run("--repeat-count=3", "--repeat-minutes=5");

		assertEquals(List.of(300_000L, 300_000L), waits);
		String text = reportText();
		assertTrue(text.contains("회차 1/3"), text);
		assertTrue(text.contains("회차 3/3"), text);
		assertEquals(3, text.split("=== 조사 끝", -1).length - 1, text);
		// 유의사항 집계(종목마다 조회)는 첫 회차에만 한다
		verify(stockWarningService, times(1)).getWarnings("A");
		verify(signalInputService, times(3)).getDailyCandles("A");
	}

	@Test
	void withoutRepeatOptionsItRunsOnceAndNeverWaits() throws Exception {
		RankingPage same = page(ranking(1, "A"));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(same);
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(same);
		givenSteadyInputs("A");
		List<Long> waits = new java.util.ArrayList<>();

		runnerWithWaiter(waits).run();

		assertEquals(List.of(), waits);
		assertFalse(reportText().contains("회차"), reportText());
		assertFalse(reportText().contains("[변화]"), reportText());
	}

	@Test
	void laterRoundsShowOnlyWhatChangedSinceThePreviousRound() throws Exception {
		RankingPage same = page(ranking(1, "A"));
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(same);
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(same);
		List<Candle> before = List.of(candle("2026-09-28"), candle("2026-09-25"));
		List<Candle> after = List.of(new Candle("2026-09-28T00:00+09:00", "100", "110", "90", "106", "1500", "KRW"), candle("2026-09-25"));
		// 1회차와 2회차는 같은 값, 3회차에서 종가와 거래량이 바뀐다
		when(signalInputService.getDailyCandles("A")).thenReturn(before, before, after);
		when(signalInputService.getInstitutionFlows("A")).thenReturn(List.of(flow("2026-09-28")));

		runnerWithWaiter(new java.util.ArrayList<>()).run("--repeat-count=3");

		String text = reportText();
		assertTrue(text.contains("[변화] 직전 회차("), text);
		assertTrue(text.contains("대비 달라진 값 없음"), text);
		assertTrue(text.contains("대비 달라진 값 2개"), text);
		assertTrue(text.contains("A 일봉 0번 종가: 105 -> 106"), text);
		assertTrue(text.contains("A 일봉 0번 거래량: 1000 -> 1500"), text);
	}

	@Test
	void sampleSymbolsChosenInTheFirstRoundStayTheSameInLaterRounds() throws Exception {
		when(rankingService.getMarketTradingAmountPage(org.mockito.ArgumentMatchers.eq("KR"), org.mockito.ArgumentMatchers.eq("1d"),
			org.mockito.ArgumentMatchers.eq(100), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenReturn(page(ranking(1, "A"), ranking(2, "B")), page(ranking(1, "A"), ranking(2, "B")),
				page(ranking(1, "C"), ranking(2, "D")), page(ranking(1, "C"), ranking(2, "D")));
		givenSteadyInputs("A");
		givenSteadyInputs("B");

		runnerWithWaiter(new java.util.ArrayList<>()).run("--repeat-count=2");

		verify(signalInputService, times(2)).getDailyCandles("A");
		verify(signalInputService, times(2)).getDailyCandles("B");
		verify(signalInputService, never()).getDailyCandles("C");
	}

	@Test
	void changeLinesReportNewAndMissingObservationsToo() {
		List<String> lines = new java.util.ArrayList<>();
		java.util.Map<String, String> previous = new java.util.LinkedHashMap<>();
		previous.merge("가", "1", (a, b) -> b);
		previous.merge("나", "2", (a, b) -> b);
		java.util.Map<String, String> current = new java.util.LinkedHashMap<>();
		current.merge("가", "1", (a, b) -> b);
		current.merge("다", "3", (a, b) -> b);

		PaperProbeRunner.changeLines(lines, "20:05:00", previous, current);

		assertEquals("[변화] 직전 회차(20:05:00) 대비 달라진 값 2개", lines.get(0));
		assertTrue(lines.contains("  다: (이전 회차에 없음) -> 3"), lines.toString());
		assertTrue(lines.contains("  나: 2 -> (이번 회차에 관측 못 함)"), lines.toString());
	}

	@Test
	void numericOptionsFallBackToDefaultsAndAreClamped() {
		assertEquals(7, PaperProbeRunner.intArg(new String[] {"--repeat-count=abc", "X"}, "--repeat-minutes=", 7, 1, 120));
		assertEquals(10, PaperProbeRunner.intArg(new String[] {"--repeat-minutes=10"}, "--repeat-minutes=", 7, 1, 120));
		assertEquals(120, PaperProbeRunner.intArg(new String[] {"--repeat-minutes=9999"}, "--repeat-minutes=", 7, 1, 120));
		assertEquals(1, PaperProbeRunner.intArg(new String[] {"--repeat-count=0"}, "--repeat-count=", 1, 1, 200));
		assertEquals(7, PaperProbeRunner.intArg(new String[] {"--repeat-minutes=abc"}, "--repeat-minutes=", 7, 1, 120));
	}

	@Test
	void everyRunAppendsANewSectionToTheSameFile() throws Exception {
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false)).thenReturn(page());
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, true)).thenReturn(page());

		runner.run();
		runner.run();

		String text = reportText();
		assertEquals(2, text.split("=== 조사 시작", -1).length - 1, text);
		assertEquals(2, text.split("=== 조사 끝", -1).length - 1, text);
	}

	@Test
	void formattersHandleOffsetsAndMissingValues() {
		assertEquals("2026-09-28 20:05:00", PaperProbeRunner.formatRankedAt("2026-09-28T20:05:00+09:00"));
		assertEquals("2026-09-28 20:05:00", PaperProbeRunner.formatRankedAt("2026-09-28T11:05:00Z"));
		assertEquals("없음(집계 없음)", PaperProbeRunner.formatRankedAt(null));
		assertEquals("2026-09-28 20:05:00", PaperProbeRunner.formatKst(Instant.parse("2026-09-28T11:05:00Z")));
		assertEquals("없음", PaperProbeRunner.formatKst(null));
	}

	@Test
	void onlyInReturnsSymbolsMissingFromTheOtherListInRankOrder() {
		List<Ranking> off = List.of(ranking(1, "A"), ranking(2, "B"), ranking(3, "C"), ranking(4, "D"));
		List<Ranking> on = List.of(ranking(1, "A"), ranking(2, "C"), ranking(3, "E"));

		assertEquals(List.of("B", "D"), PaperProbeRunner.onlyIn(off, on).stream().map(Ranking::symbol).toList());
		assertEquals(List.of("E"), PaperProbeRunner.onlyIn(on, off).stream().map(Ranking::symbol).toList());
		assertEquals(List.of(), PaperProbeRunner.onlyIn(off, off));
		assertEquals(List.of(), PaperProbeRunner.onlyIn(List.of(), on));
	}
}
