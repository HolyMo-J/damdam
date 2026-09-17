package com.damdam.bot.liquidation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

// 자동 매도의 하루 횟수 한도와 연속 손실 정지를 관리한다 (버그 폭주 방지, 나쁜 전략 조기 정지)
@Component
class AutoSellGuard {

	private static final Logger log = LoggerFactory.getLogger(AutoSellGuard.class);

	private final int maxDailyCount;
	private final int maxConsecutiveLosses;
	private final Path stateFilePath;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private LocalDate dailyCountDate = LocalDate.MIN;
	private int dailyCount;

	AutoSellGuard(
			@Value("${damdam.orders.max-daily-count}") int maxDailyCount,
			@Value("${damdam.orders.max-consecutive-losses}") int maxConsecutiveLosses,
			@Value("${damdam.orders.guard-state-path}") String stateFilePath) {
		this.maxDailyCount = maxDailyCount;
		this.maxConsecutiveLosses = maxConsecutiveLosses;
		this.stateFilePath = Path.of(stateFilePath);
	}

	synchronized boolean canPlaceAutoSell(String symbol) {
		resetDailyCountIfNewDay();
		if (dailyCount >= maxDailyCount) {
			log.warn("[안전장치] 오늘 자동 매도 횟수 한도({}회)에 도달해 {} 자동 매도를 건너뜁니다.", maxDailyCount, symbol);
			return false;
		}
		GuardState state = readState();
		if (state.paused()) {
			log.warn("[안전장치] 연속 손실 {}회로 자동 매도가 정지된 상태입니다. {}는 건너뜁니다. "
				+ "검토 후 재개하려면 bot/data/auto_sell_guard.json을 확인하세요.", state.consecutiveLosses(), symbol);
			return false;
		}
		return true;
	}

	synchronized void recordAttempt(boolean isLoss) {
		dailyCount++;
		GuardState state = readState();
		int losses = isLoss ? state.consecutiveLosses() + 1 : 0;
		boolean paused = state.paused() || losses >= maxConsecutiveLosses;
		writeState(new GuardState(losses, paused));
		if (paused && !state.paused()) {
			log.warn("[안전장치] 연속 손실 {}회에 도달해 자동 매도를 정지합니다. 전략 재검토가 필요합니다.", losses);
		}
	}

	private void resetDailyCountIfNewDay() {
		LocalDate today = LocalDate.now();
		if (!today.equals(dailyCountDate)) {
			dailyCountDate = today;
			dailyCount = 0;
		}
	}

	private GuardState readState() {
		if (!Files.exists(stateFilePath)) {
			return GuardState.initial();
		}
		try {
			return objectMapper.readValue(stateFilePath.toFile(), GuardState.class);
		} catch (JacksonException e) {
			log.warn("안전장치 상태 파일을 읽지 못해 초기 상태로 취급합니다.");
			return GuardState.initial();
		}
	}

	private void writeState(GuardState state) {
		try {
			Path parent = stateFilePath.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			objectMapper.writeValue(stateFilePath.toFile(), state);
		} catch (IOException | JacksonException e) {
			log.warn("안전장치 상태 저장 실패: {}", e.getMessage());
		}
	}
}
