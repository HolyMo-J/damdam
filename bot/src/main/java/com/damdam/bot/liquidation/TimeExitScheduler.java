package com.damdam.bot.liquidation;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// listen 프로필로 봇이 떠 있는 동안, 평일 장 시작 직후 한 번 시간 청산 기준을 확인한다
@Component
@Profile("listen")
public class TimeExitScheduler {

	private final HoldingTimeExitService holdingTimeExitService;

	public TimeExitScheduler(HoldingTimeExitService holdingTimeExitService) {
		this.holdingTimeExitService = holdingTimeExitService;
	}

	@Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Seoul")
	public void checkTimeExit() {
		holdingTimeExitService.checkAndAlert();
	}
}
