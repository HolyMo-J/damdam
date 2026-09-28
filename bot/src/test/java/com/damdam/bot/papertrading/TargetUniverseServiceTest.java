package com.damdam.bot.papertrading;

import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingPage;
import com.damdam.bot.ranking.RankingPrice;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import com.damdam.bot.stocks.StockWarning;
import com.damdam.bot.stocks.StockWarningLookupException;
import com.damdam.bot.stocks.StockWarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetUniverseServiceTest {

	private RankingService rankingService;
	private StockInfoService stockInfoService;
	private StockWarningService stockWarningService;
	private TargetUniverseService service;

	@BeforeEach
	void setUp() {
		rankingService = mock(RankingService.class);
		stockInfoService = mock(StockInfoService.class);
		stockWarningService = mock(StockWarningService.class);
		service = new TargetUniverseService(rankingService, stockInfoService, stockWarningService, 0);
	}

	private static Ranking ranking(int rank, String symbol) {
		return new Ranking(rank, symbol, "KRW", new RankingPrice("1000", "990", "0.01"), "100", "1000000");
	}

	private static StockInfo stock(String symbol, String securityType, boolean suspended) {
		return new StockInfo(symbol, "이름" + symbol, "KOSPI", securityType, true, "ACTIVE",
			new StockInfo.KoreanMarketDetail(false, true, suspended, false));
	}

	private void givenRankings(Ranking... rankings) {
		when(rankingService.getMarketTradingAmountPage("KR", "1d", 100, false))
			.thenReturn(new RankingPage("2026-09-28T20:17:36+09:00", List.of(rankings)));
	}

	@Test
	void keepsRankOrderAndCollectsExclusionsWithReasons() {
		givenRankings(ranking(1, "A"), ranking(2, "B"), ranking(3, "C"), ranking(4, "D"), ranking(5, "E"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(
			stock("A", "STOCK", false),
			stock("B", "ETF", false),          // 정보 단계에서 제외
			stock("C", "STOCK", false),        // 경고 때문에 제외
			stock("D", "STOCK", true),         // 거래정지
			stock("E", "STOCK", false)));
		when(stockWarningService.getWarnings("A")).thenReturn(List.of());
		when(stockWarningService.getWarnings("C")).thenReturn(List.of(new StockWarning("INVESTMENT_RISK", "KRX", "2026-09-01", null)));
		when(stockWarningService.getWarnings("E")).thenReturn(List.of(new StockWarning("VI_STATIC", "KRX", null, null)));

		TargetUniverse universe = service.build();

		assertEquals(List.of(
			new TargetUniverse.Member(1, "A", "이름A", "1000000"),
			new TargetUniverse.Member(5, "E", "이름E", "1000000")), universe.members());
		assertEquals(List.of(
			new TargetUniverse.Exclusion(2, "B", ExclusionReason.NOT_ORDINARY_STOCK),
			new TargetUniverse.Exclusion(3, "C", ExclusionReason.INVESTMENT_RISK),
			new TargetUniverse.Exclusion(4, "D", ExclusionReason.TRADING_SUSPENDED)), universe.exclusions());
	}

	@Test
	void doesNotCallWarningsForSymbolsAlreadyExcludedByInfo() {
		givenRankings(ranking(1, "A"), ranking(2, "B"), ranking(3, "C"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(stock("A", "ETF", false), stock("C", "STOCK", false))); // B는 응답에 없음
		when(stockWarningService.getWarnings("C")).thenReturn(List.of());

		TargetUniverse universe = service.build();

		verify(stockWarningService, never()).getWarnings("A");
		verify(stockWarningService, never()).getWarnings("B");
		assertEquals(List.of(new TargetUniverse.Member(3, "C", "이름C", "1000000")), universe.members());
		assertEquals(List.of(
			new TargetUniverse.Exclusion(1, "A", ExclusionReason.NOT_ORDINARY_STOCK),
			new TargetUniverse.Exclusion(2, "B", ExclusionReason.INFO_MISSING)), universe.exclusions());
	}

	@Test
	void warningLookupFailureExcludesOnlyThatSymbol() {
		givenRankings(ranking(1, "A"), ranking(2, "B"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(stock("A", "STOCK", false), stock("B", "STOCK", false)));
		when(stockWarningService.getWarnings("A")).thenThrow(new StockWarningLookupException("A", "HTTP 500"));
		when(stockWarningService.getWarnings("B")).thenReturn(List.of());

		TargetUniverse universe = service.build();

		assertEquals(List.of(new TargetUniverse.Member(2, "B", "이름B", "1000000")), universe.members());
		assertEquals(List.of(new TargetUniverse.Exclusion(1, "A", ExclusionReason.WARNING_LOOKUP_FAILED)), universe.exclusions());
	}

	@Test
	void passesAllRankedSymbolsInOrderToStockLookup() {
		givenRankings(ranking(1, "A"), ranking(2, "B"));
		when(stockInfoService.getStocks(List.of("A", "B"))).thenReturn(List.of(stock("A", "STOCK", false)));
		when(stockWarningService.getWarnings("A")).thenReturn(List.of());

		service.build();

		// 순위 조회는 전 거래일(1d) 기준 상위 100위 KR이고, 투자유의 제외 옵션은 끈다(마지막 인자 false). 이 인자로 불려야 givenRankings의 스텁이 응답한다
		verify(rankingService).getMarketTradingAmountPage("KR", "1d", 100, false);
		verify(rankingService, never()).getMarketTradingAmountPage("KR", "1d", 100, true);
		verify(stockInfoService).getStocks(List.of("A", "B"));
	}

	@Test
	void emptyRankingFailsInsteadOfReturningAnEmptyUniverse() {
		when(rankingService.getMarketTradingAmountPage(anyString(), anyString(), anyInt(), anyBoolean())).thenReturn(new RankingPage(null, List.of()));
		assertThrows(IllegalStateException.class, () -> service.build());
	}

	@Test
	void emptyStockInfoResponseFailsInsteadOfExcludingEverything() {
		givenRankings(ranking(1, "A"), ranking(2, "B"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of()); // 응답 본문이 비면 서비스가 빈 목록을 돌려준다
		assertThrows(IllegalStateException.class, () -> service.build());
		verify(stockWarningService, never()).getWarnings(anyString());
	}

	@Test
	void warningApiOutageFailsInsteadOfReturningAnEmptyUniverse() {
		givenRankings(ranking(1, "A"), ranking(2, "B"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(stock("A", "STOCK", false), stock("B", "STOCK", false)));
		when(stockWarningService.getWarnings(anyString())).thenThrow(new StockWarningLookupException("X", "HTTP 403"));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.build());
		assertTrue(e.getMessage().contains("경고 조회 실패 2종목"), e.getMessage());
	}

	@Test
	void everythingLegitimatelyFilteredAlsoFails() {
		givenRankings(ranking(1, "A"));
		when(stockInfoService.getStocks(anyList())).thenReturn(List.of(stock("A", "ETF", false)));
		assertThrows(IllegalStateException.class, () -> service.build());
	}

	@Test
	void bulkLookupFailurePropagates() {
		givenRankings(ranking(1, "A"));
		when(stockInfoService.getStocks(anyList())).thenThrow(new IllegalStateException("boom"));
		assertThrows(IllegalStateException.class, () -> service.build());
	}
}
