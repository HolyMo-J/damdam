package com.damdam.bot.papertrading;

import com.damdam.bot.papertrading.InvestorHistoryProbeRunner.Summary;
import com.damdam.bot.stocks.InvestorTradingHistory.StopReason;
import com.damdam.bot.stocks.InvestorTradingPage;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.InvestorTradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 보관 기간 조사의 종료 판정을 가짜 서비스로 확인한다. 특히 "우리 상한에 걸려 멈춘 것"과 "API가 끝났다고 한 것"을 섞어 보고하지 않는지가 핵심이다
class InvestorHistoryProbeRunnerTest {

	private InvestorTradingService service;
	private InvestorHistoryProbeRunner runner;

	@BeforeEach
	void setUp() {
		service = mock(InvestorTradingService.class);
		runner = new InvestorHistoryProbeRunner(service, 0, Path.of("unused"));
	}

	private static InvestorTradingRecord record(String date, String netBuy) {
		return new InvestorTradingRecord(LocalDate.parse(date), new BigDecimal(netBuy), null);
	}

	@Test
	void walksBackUntilTheApiSaysThereIsNoMoreAndReportsTheOldestDate() throws Exception {
		when(service.getRecordsPage("A", 100, null)).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-28", "5"), record("2026-09-25", "0")), LocalDate.parse("2026-09-24")));
		when(service.getRecordsPage("A", 100, LocalDate.parse("2026-09-24"))).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-24", "-3"), record("2025-12-30", "7")), null));

		Summary summary = runner.probe("A");

		assertEquals(StopReason.API_END, summary.stopReason());
		assertEquals(2, summary.pages());
		assertEquals(4, summary.recordCount());
		assertEquals(LocalDate.parse("2025-12-30"), summary.oldest());
		assertEquals(LocalDate.parse("2026-09-28"), summary.newest());
		// 가장 큰 간격은 2025-12-30 ~ 2026-09-24 사이다
		assertEquals(LocalDate.parse("2025-12-30"), summary.gapFrom());
		assertEquals(LocalDate.parse("2026-09-24"), summary.gapTo());
		// 기관 순매수가 0인 기록은 연도별로 따로 센다 (기본값으로 채운 오래된 기록을 가려내려는 것)
		assertEquals(3, summary.perYear().get(2026)[0]);
		assertEquals(1, summary.perYear().get(2026)[1]);
		assertEquals(1, summary.perYear().get(2025)[0]);
	}

	@Test
	void datesRepeatedAtThePageBoundaryAreCountedOnce() throws Exception {
		// until이 포함 기준이라 다음 페이지 첫 기록이 앞 페이지 마지막 기록과 같은 날일 수 있다
		when(service.getRecordsPage("A", 100, null)).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-28", "1"), record("2026-09-25", "1")), LocalDate.parse("2026-09-25")));
		when(service.getRecordsPage("A", 100, LocalDate.parse("2026-09-25"))).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-25", "1"), record("2026-09-24", "1")), null));

		assertEquals(3, runner.probe("A").recordCount());
	}

	@Test
	void stoppingAtTheOwnPageCapIsReportedAsCapNotAsTheRetentionLimit() throws Exception {
		// 매 페이지가 새 날짜를 주고 nextUntil도 계속 이어지면 상한(60페이지)에서 멈춘다. 이 날짜를 보관 한계로 적으면 안 된다
		for (int page = 0; page < InvestorHistoryProbeRunner.MAX_PAGES + 5; page++) {
			LocalDate until = page == 0 ? null : LocalDate.parse("2026-09-28").minusDays(page);
			LocalDate date = LocalDate.parse("2026-09-28").minusDays(page);
			when(service.getRecordsPage("A", 100, until)).thenReturn(
				new InvestorTradingPage(List.of(record(date.toString(), "1")), date.minusDays(1)));
		}

		Summary summary = runner.probe("A");

		assertEquals(StopReason.PAGE_CAP, summary.stopReason());
		assertEquals(InvestorHistoryProbeRunner.MAX_PAGES, summary.pages());
		verify(service, times(InvestorHistoryProbeRunner.MAX_PAGES)).getRecordsPage(eq("A"), eq(100), any());
		assertTrue(InvestorHistoryProbeRunner.describe(summary).stream().anyMatch(line -> line.contains("보관 한계가 아니다")));
	}

	@Test
	void anEmptyFirstPageIsReportedWithoutADateRange() throws Exception {
		when(service.getRecordsPage("A", 100, null)).thenReturn(new InvestorTradingPage(List.of(), null));

		Summary summary = runner.probe("A");

		assertEquals(StopReason.EMPTY_PAGE, summary.stopReason());
		assertEquals(0, summary.recordCount());
		assertNull(summary.oldest());
	}

	@Test
	void aPageThatDoesNotMoveBackwardStopsInsteadOfLoopingForever() throws Exception {
		LocalDate stuck = LocalDate.parse("2026-09-24");
		when(service.getRecordsPage("A", 100, null)).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-28", "1")), stuck));
		when(service.getRecordsPage("A", 100, stuck)).thenReturn(new InvestorTradingPage(
			List.of(record("2026-09-24", "1")), stuck));

		Summary summary = runner.probe("A");

		assertEquals(StopReason.NO_PROGRESS, summary.stopReason());
		assertEquals(2, summary.pages());
	}

	@Test
	void runnerIsCreatedOnlyWhenItsProfileIsActive() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("investor-history-probe");
			context.registerBean(InvestorTradingService.class, () -> mock(InvestorTradingService.class));
			context.register(InvestorHistoryProbeRunner.class);
			context.refresh();
			assertEquals(1, context.getBeansOfType(InvestorHistoryProbeRunner.class).size());
		}
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("query");
			context.registerBean(InvestorTradingService.class, () -> mock(InvestorTradingService.class));
			context.register(InvestorHistoryProbeRunner.class);
			context.refresh();
			assertEquals(0, context.getBeansOfType(InvestorHistoryProbeRunner.class).size());
		}
	}
}
