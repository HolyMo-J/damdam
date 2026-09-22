package com.damdam.bot.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// query 프로필에서 오늘, 지난 개장일, 다음 개장일이 API 응답과 실제(WTS/뉴스)에 맞는지 눈으로 확인한다
@Component
@Profile("query")
@Order(7)
public class MarketCalendarQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(MarketCalendarQueryRunner.class);

	private final MarketCalendarService marketCalendarService;

	public MarketCalendarQueryRunner(MarketCalendarService marketCalendarService) {
		this.marketCalendarService = marketCalendarService;
	}

	@Override
	public void run(String... args) {
		LocalDate today = LocalDate.now();
		for (int offset = -3; offset <= 3; offset++) {
			LocalDate date = today.plusDays(offset);
			boolean tradingDay = marketCalendarService.isTradingDay(date);
			log.info("[국내 캘린더] {} ({}) 개장일: {}", date, date.getDayOfWeek(), tradingDay);
		}
	}
}
