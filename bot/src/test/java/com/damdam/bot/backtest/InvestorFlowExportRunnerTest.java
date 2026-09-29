package com.damdam.bot.backtest;

import com.damdam.bot.stocks.InvestorTradingHistory;
import com.damdam.bot.stocks.InvestorTradingHistory.StopReason;
import com.damdam.bot.stocks.InvestorTradingPage;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.InvestorTradingService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestorFlowExportRunnerTest {

	@Test
	void readsTheSymbolColumnEvenWhenTheNameContainsAComma() {
		List<String> lines = List.of(
			"rank,symbol,name,trading_amount,duration,exclude_caution,ranked_at",
			"1,000660,\"SK하이닉스\",7128871469000,1d,false,2026-09-29T20:17:47.365+09:00",
			"5,0035S0,\"이름, 쉼표 포함\",100,1d,false,2026-09-29T20:17:47.365+09:00",
			"");

		assertEquals(List.of("000660", "0035S0"), InvestorFlowExportRunner.parseSymbols(lines));
	}

	@Test
	void aBrokenLineFailsInsteadOfBeingSkipped() {
		// 종목이 조용히 빠지면 빈도를 적게 세게 된다
		assertThrows(IllegalArgumentException.class, () -> InvestorFlowExportRunner.parseSymbols(List.of("header", "1")));
		assertThrows(IllegalArgumentException.class, () -> InvestorFlowExportRunner.parseSymbols(List.of("header", "1,,name")));
	}

	@Test
	void csvIsOldestFirstWithSignedVolumesAndBlankUpdatedAtWhenMissing() {
		TreeMap<LocalDate, InvestorTradingRecord> byDate = new TreeMap<>();
		byDate.put(LocalDate.parse("2026-09-28"), new InvestorTradingRecord(LocalDate.parse("2026-09-28"), new BigDecimal("200"), Instant.parse("2026-09-28T11:05:00Z")));
		byDate.put(LocalDate.parse("2026-09-25"), new InvestorTradingRecord(LocalDate.parse("2026-09-25"), new BigDecimal("-250"), null));

		String csv = InvestorFlowExportRunner.toCsv(new InvestorTradingHistory.Result(1, byDate, StopReason.API_END));

		assertEquals("""
			date,institution_net_buy_volume,updated_at
			2026-09-25,-250,
			2026-09-28,200,2026-09-28T11:05:00Z
			""", csv);
	}

	@Test
	void walkerCollectsTheWholeHistoryAndDeduplicatesTheBoundaryDate() throws Exception {
		InvestorTradingService service = mock(InvestorTradingService.class);
		when(service.getRecordsPage("A", 100, null)).thenReturn(new InvestorTradingPage(
			List.of(rec("2026-09-28", "1"), rec("2026-09-25", "2")), LocalDate.parse("2026-09-25")));
		when(service.getRecordsPage("A", 100, LocalDate.parse("2026-09-25"))).thenReturn(new InvestorTradingPage(
			List.of(rec("2026-09-25", "2"), rec("2026-09-24", "3")), null));

		InvestorTradingHistory.Result result = InvestorTradingHistory.walk(service, "A", 100, 60, 0);

		assertEquals(StopReason.API_END, result.stopReason());
		assertEquals(List.of(LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-25"), LocalDate.parse("2026-09-28")),
			List.copyOf(result.byDate().keySet()));
	}

	@Test
	void runnerIsCreatedOnlyWhenItsProfileIsActive() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("export-investor");
			context.registerBean(InvestorTradingService.class, () -> mock(InvestorTradingService.class));
			context.register(InvestorFlowExportRunner.class);
			context.refresh();
			assertEquals(1, context.getBeansOfType(InvestorFlowExportRunner.class).size());
		}
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("export-universe");
			context.registerBean(InvestorTradingService.class, () -> mock(InvestorTradingService.class));
			context.register(InvestorFlowExportRunner.class);
			context.refresh();
			assertEquals(0, context.getBeansOfType(InvestorFlowExportRunner.class).size());
		}
	}

	private static InvestorTradingRecord rec(String date, String netBuy) {
		return new InvestorTradingRecord(LocalDate.parse(date), new BigDecimal(netBuy), null);
	}
}
