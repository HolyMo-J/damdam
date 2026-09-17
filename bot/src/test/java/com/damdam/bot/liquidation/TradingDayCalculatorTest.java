package com.damdam.bot.liquidation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingDayCalculatorTest {

	private final TradingDayCalculator calculator = new TradingDayCalculator();

	@Test
	void countsOnlyWeekdays() {
		// 2026-03-02(월) ~ 2026-03-09(월): 화수목금월 = 5거래일
		LocalDate from = LocalDate.of(2026, 3, 2);
		LocalDate to = LocalDate.of(2026, 3, 9);

		assertEquals(5, calculator.tradingDaysBetween(from, to));
	}

	@Test
	void sameDayIsZero() {
		LocalDate date = LocalDate.of(2026, 3, 2);
		assertEquals(0, calculator.tradingDaysBetween(date, date));
	}
}
