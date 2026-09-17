package com.damdam.bot.liquidation;

import java.time.DayOfWeek;
import java.time.LocalDate;

// 거래일은 월~금으로만 계산한다 (v0: 공휴일은 반영하지 않음)
class TradingDayCalculator {

	long tradingDaysBetween(LocalDate from, LocalDate to) {
		if (!to.isAfter(from)) {
			return 0;
		}
		long count = 0;
		LocalDate date = from;
		while (date.isBefore(to)) {
			date = date.plusDays(1);
			if (isTradingDay(date)) {
				count++;
			}
		}
		return count;
	}

	private boolean isTradingDay(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}
}
