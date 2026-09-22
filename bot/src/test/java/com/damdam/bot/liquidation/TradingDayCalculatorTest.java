package com.damdam.bot.liquidation;

import com.damdam.bot.market.MarketCalendarService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TradingDayCalculatorTest {

	private MarketCalendarService allTradingDays() {
		MarketCalendarService service = Mockito.mock(MarketCalendarService.class);
		when(service.isTradingDay(any())).thenReturn(true);
		return service;
	}

	@Test
	void 해외는_국내_캘린더를_호출하지_않고_요일만_본다() {
		MarketCalendarService service = Mockito.mock(MarketCalendarService.class);
		TradingDayCalculator calculator = new TradingDayCalculator(service);
		// 2026-03-02(월) ~ 2026-03-09(월): 화수목금월 = 5거래일
		LocalDate from = LocalDate.of(2026, 3, 2);
		LocalDate to = LocalDate.of(2026, 3, 9);

		assertEquals(5, calculator.tradingDaysBetween(from, to, false));
		Mockito.verifyNoInteractions(service);
	}

	@Test
	void 같은_날은_0거래일이다() {
		TradingDayCalculator calculator = new TradingDayCalculator(allTradingDays());
		LocalDate date = LocalDate.of(2026, 3, 2);
		assertEquals(0, calculator.tradingDaysBetween(date, date, true));
	}

	@Test
	void 국내는_캘린더가_휴장일이라고_하는_평일을_거래일에서_뺀다() {
		// 2026-03-02(월)~2026-03-06(금)이 설날 연휴로 전부 휴장이라고 가정
		Set<LocalDate> holidays = Set.of(
			LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5));
		MarketCalendarService service = Mockito.mock(MarketCalendarService.class);
		when(service.isTradingDay(any())).thenAnswer(inv -> !holidays.contains(inv.getArgument(0)));
		TradingDayCalculator calculator = new TradingDayCalculator(service);

		// 2026-03-02(월) ~ 2026-03-09(월): 화수목이 휴장이라 금, 월만 거래일 = 2거래일
		LocalDate from = LocalDate.of(2026, 3, 2);
		LocalDate to = LocalDate.of(2026, 3, 9);
		assertEquals(2, calculator.tradingDaysBetween(from, to, true));
	}

	@Test
	void 캘린더_조회가_실패하면_요일만으로_폴백한다() {
		MarketCalendarService service = Mockito.mock(MarketCalendarService.class);
		when(service.isTradingDay(any())).thenThrow(new RuntimeException("네트워크 오류"));
		TradingDayCalculator calculator = new TradingDayCalculator(service);

		LocalDate from = LocalDate.of(2026, 3, 2);
		LocalDate to = LocalDate.of(2026, 3, 9);
		assertEquals(5, calculator.tradingDaysBetween(from, to, true));
	}
}
