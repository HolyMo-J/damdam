package com.damdam.bot.liquidation;

import com.damdam.bot.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;

// 거래일 계산. 국내는 market-calendar/KR로 실제 휴장일을 반영하고, 해외는 시간 청산이 알림만 하므로
// 요일만 보는 근사치를 유지한다 (docs/review-tasks.md 9번, 해외 휴장일 캘린더는 다루지 않기로 함)
class TradingDayCalculator {

	private static final Logger log = LoggerFactory.getLogger(TradingDayCalculator.class);

	private final MarketCalendarService marketCalendarService;

	TradingDayCalculator(MarketCalendarService marketCalendarService) {
		this.marketCalendarService = marketCalendarService;
	}

	long tradingDaysBetween(LocalDate from, LocalDate to, boolean domestic) {
		if (!to.isAfter(from)) {
			return 0;
		}
		long count = 0;
		LocalDate date = from;
		while (date.isBefore(to)) {
			date = date.plusDays(1);
			if (isTradingDay(date, domestic)) {
				count++;
			}
		}
		return count;
	}

	private boolean isTradingDay(LocalDate date, boolean domestic) {
		DayOfWeek day = date.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
			return false;
		}
		if (!domestic) {
			return true;
		}
		try {
			return marketCalendarService.isTradingDay(date);
		} catch (Exception e) {
			// 실패해도 시간 청산 자체를 막지 않는다. 요일만 보는 근사치로 폴백(휴일이 낀 주에 하루 일찍 판정될 수 있음, 감수하기로 한 범위)
			log.warn("[거래일 계산] {} 국내 개장일 조회 실패, 요일만으로 판정합니다: {}", date, e.getMessage());
			return true;
		}
	}
}
