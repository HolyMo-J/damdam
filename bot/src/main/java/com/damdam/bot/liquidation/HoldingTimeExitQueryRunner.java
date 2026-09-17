package com.damdam.bot.liquidation;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// query 프로필에서 스케줄을 기다리지 않고 바로 시간 청산 기준을 확인해본다
@Component
@Profile("query")
@Order(3)
public class HoldingTimeExitQueryRunner implements CommandLineRunner {

	private final HoldingTimeExitService holdingTimeExitService;

	public HoldingTimeExitQueryRunner(HoldingTimeExitService holdingTimeExitService) {
		this.holdingTimeExitService = holdingTimeExitService;
	}

	@Override
	public void run(String... args) {
		holdingTimeExitService.checkAndAlert();
	}
}
