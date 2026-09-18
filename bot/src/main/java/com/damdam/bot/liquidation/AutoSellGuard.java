package com.damdam.bot.liquidation;

import com.damdam.bot.notification.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;

// 자동 매도의 하루 횟수 한도와 연속 손실 정지를 관리한다 (버그 폭주 방지, 나쁜 전략 조기 정지).
// 실패 시 닫히는(fail-closed) 방식이다: 상태 파일이 있는데 못 읽거나, 상태를 파일에 저장하지 못하면 주문을 막는다.
// 파일이 아예 없는 것은 "처음 실행" 또는 사용자가 정지를 풀려고 지운 경우라서 초기 상태로 본다.
@Component
class AutoSellGuard {

	private static final Logger log = LoggerFactory.getLogger(AutoSellGuard.class);

	private final int maxDailyCount;
	private final int maxConsecutiveLosses;
	private final Path stateFilePath;
	private final Clock clock;
	private final Notifier notifier;
	private final ObjectMapper objectMapper = new ObjectMapper();

	// 마지막으로 저장에 실패한 상태. 값이 있으면 파일이 최신이 아니므로 저장에 성공하기 전까지 주문을 막는다
	private GuardState unsavedState;

	@Autowired
	AutoSellGuard(
			@Value("${damdam.orders.max-daily-count}") int maxDailyCount,
			@Value("${damdam.orders.max-consecutive-losses}") int maxConsecutiveLosses,
			@Value("${damdam.orders.guard-state-path}") String stateFilePath,
			Notifier notifier) {
		this(maxDailyCount, maxConsecutiveLosses, stateFilePath, Clock.systemDefaultZone(), notifier);
	}

	AutoSellGuard(int maxDailyCount, int maxConsecutiveLosses, String stateFilePath, Clock clock, Notifier notifier) {
		this.maxDailyCount = maxDailyCount;
		this.maxConsecutiveLosses = maxConsecutiveLosses;
		this.stateFilePath = Path.of(stateFilePath);
		this.clock = clock;
		this.notifier = notifier;
	}

	synchronized boolean canPlaceAutoSell(String symbol) {
		if (unsavedState != null) {
			if (!writeState(unsavedState)) {
				log.warn("[안전장치] 상태 파일에 저장하지 못한 상태라 안전을 위해 {} 자동 매도를 건너뜁니다.", symbol);
				notifier.send("guard-unsaved", "[담담] 안전장치 상태를 파일에 저장하지 못해 자동 매도를 막았습니다. 디스크와 bot/data 폴더를 확인하세요.");
				return false;
			}
			unsavedState = null;
		}
		GuardState state = readState();
		if (state.paused()) {
			log.warn("[안전장치] 자동 매도가 정지된 상태입니다(연속 손실 {}회 또는 상태 파일 읽기 실패). {}는 건너뜁니다. "
				+ "검토 후 재개하려면 bot/data/auto_sell_guard.json을 확인하세요.", state.consecutiveLosses(), symbol);
			notifier.send("guard-paused", "[담담] 안전장치로 자동 매도가 정지된 상태입니다 (연속 손실 " + state.consecutiveLosses()
				+ "회 또는 상태 파일 문제). 검토 후 bot/data/auto_sell_guard.json을 확인하세요.");
			return false;
		}
		if (state.dailyCountOn(today()) >= maxDailyCount) {
			log.warn("[안전장치] 오늘 자동 매도 횟수 한도({}회)에 도달해 {} 자동 매도를 건너뜁니다.", maxDailyCount, symbol);
			notifier.send("guard-daily-limit", "[담담] 오늘 자동 매도 횟수 한도(" + maxDailyCount + "회)에 도달해 자동 매도를 건너뜁니다.");
			return false;
		}
		return true;
	}

	// 실제로 주문이 접수된 경우에만 호출한다. 모의 실행 결과를 기록하면 실전 전환 때 이미 정지 상태일 수 있다
	synchronized void recordAttempt(boolean isLoss) {
		GuardState state = readState();
		int losses = isLoss ? state.consecutiveLosses() + 1 : 0;
		boolean paused = state.paused() || losses >= maxConsecutiveLosses;
		String today = today();
		GuardState next = new GuardState(losses, paused, today, state.dailyCountOn(today) + 1);

		if (writeState(next)) {
			unsavedState = null;
		} else {
			unsavedState = next;
		}
		if (paused && !state.paused()) {
			log.warn("[안전장치] 연속 손실 {}회에 도달해 자동 매도를 정지합니다. 전략 재검토가 필요합니다.", losses);
			notifier.send("guard-paused-now", "[담담] 연속 손실 " + losses + "회에 도달해 자동 매도를 정지했습니다. 전략 재검토가 필요합니다.");
		}
	}

	private String today() {
		return LocalDate.now(clock).toString();
	}

	private GuardState readState() {
		if (!Files.exists(stateFilePath)) {
			return GuardState.initial();
		}
		try {
			return objectMapper.readValue(stateFilePath.toFile(), GuardState.class);
		} catch (JacksonException e) {
			notifier.send("guard-unreadable", "[담담] 안전장치 상태 파일을 읽지 못해 자동 매도를 정지 상태로 취급합니다. bot/data/auto_sell_guard.json을 확인하세요.");
			log.error("안전장치 상태 파일을 읽지 못해 자동 매도를 정지 상태로 취급합니다. 파일을 확인하거나 지워서 초기화하세요. 원인: {}",
				e.getOriginalMessage());
			return GuardState.unreadable();
		}
	}

	// 임시 파일에 쓴 뒤 교체해서, 쓰는 도중 종료돼도 깨진 파일이 남지 않게 한다
	private boolean writeState(GuardState state) {
		try {
			Path absolute = stateFilePath.toAbsolutePath();
			Path parent = absolute.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
			objectMapper.writeValue(temp.toFile(), state);
			Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | JacksonException e) {
			log.error("안전장치 상태 저장 실패: {}", e.getMessage());
			return false;
		}
	}
}
