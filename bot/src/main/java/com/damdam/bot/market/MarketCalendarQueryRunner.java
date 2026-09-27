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
	public void run(String... args) throws InterruptedException {
		LocalDate today = LocalDate.now();
		for (int offset = -3; offset <= 3; offset++) {
			LocalDate date = today.plusDays(offset);
			boolean tradingDay = marketCalendarService.isTradingDay(date);
			log.info("[국내 캘린더] {} ({}) 개장일: {}", date, date.getDayOfWeek(), tradingDay);
			// 새 프로세스라 캐시가 비어 있어 7개 날짜 모두 실제 API를 부른다. 휴장일 조회는 초당 3회 제한(MARKET_INFO
			// 그룹)이라 딜레이 없이 연달아 부르면 429가 난다 (2026-09-28 실측으로 확인)
			Thread.sleep(400);
		}
	}
}
