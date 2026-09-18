package com.damdam.bot.liquidation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSellGuardTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private static Clock dayClock(String isoDate) {
		return Clock.fixed(Instant.parse(isoDate + "T03:00:00Z"), ZONE);
	}

	private static AutoSellGuard guard(int maxDaily, int maxLosses, Path file, Clock clock) {
		return new AutoSellGuard(maxDaily, maxLosses, file.toString(), clock);
	}

	@Test
	void blocksAfterDailyCountLimit(@TempDir Path tempDir) {
		AutoSellGuard guard = new AutoSellGuard(2, 10, tempDir.resolve("state.json").toString());

		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordAttempt(false);
		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordAttempt(false);

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void pausesAfterConsecutiveLosses(@TempDir Path tempDir) {
		AutoSellGuard guard = new AutoSellGuard(100, 3, tempDir.resolve("state.json").toString());

		guard.recordAttempt(true);
		guard.recordAttempt(true);
		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordAttempt(true);

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void aWinResetsConsecutiveLosses(@TempDir Path tempDir) {
		AutoSellGuard guard = new AutoSellGuard(100, 2, tempDir.resolve("state.json").toString());

		guard.recordAttempt(true);
		guard.recordAttempt(false);
		guard.recordAttempt(true);

		assertTrue(guard.canPlaceAutoSell("AAA"));
	}

	// 재시작해도 하루 횟수가 0으로 돌아가면 안 된다 (재시작으로 한도를 우회하는 경로 차단)
	@Test
	void dailyCountSurvivesRestart(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");
		Clock today = dayClock("2026-09-21");

		AutoSellGuard first = guard(2, 10, file, today);
		first.recordAttempt(false);
		first.recordAttempt(false);

		AutoSellGuard restarted = guard(2, 10, file, today);
		assertFalse(restarted.canPlaceAutoSell("AAA"));
	}

	@Test
	void dailyCountResetsOnANewDay(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");

		AutoSellGuard monday = guard(1, 10, file, dayClock("2026-09-21"));
		monday.recordAttempt(false);
		assertFalse(monday.canPlaceAutoSell("AAA"));

		AutoSellGuard tuesday = guard(1, 10, file, dayClock("2026-09-22"));
		assertTrue(tuesday.canPlaceAutoSell("AAA"));
	}

	@Test
	void pausedStateSurvivesRestart(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");
		Clock today = dayClock("2026-09-21");

		AutoSellGuard first = guard(100, 2, file, today);
		first.recordAttempt(true);
		first.recordAttempt(true);

		assertFalse(guard(100, 2, file, today).canPlaceAutoSell("AAA"));
	}

	// 파일이 없으면 처음 실행이거나 사용자가 정지를 풀려고 지운 것이므로 허용한다
	@Test
	void missingStateFileMeansInitialState(@TempDir Path tempDir) {
		assertTrue(guard(10, 3, tempDir.resolve("none.json"), dayClock("2026-09-21")).canPlaceAutoSell("AAA"));
	}

	// 파일이 있는데 읽지 못하면 초기 상태가 아니라 정지 상태로 취급한다 (fail-closed)
	@Test
	void unreadableStateFileBlocksAutoSell(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("state.json");
		Files.writeString(file, "{ 깨진 파일");

		assertFalse(guard(10, 3, file, dayClock("2026-09-21")).canPlaceAutoSell("AAA"));
	}

	@Test
	void deletingTheStateFileLiftsThePause(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("state.json");
		Clock today = dayClock("2026-09-21");
		AutoSellGuard guard = guard(100, 1, file, today);
		guard.recordAttempt(true);
		assertFalse(guard.canPlaceAutoSell("AAA"));

		Files.delete(file);

		assertTrue(guard.canPlaceAutoSell("AAA"));
	}

	// 예전 형식 파일(하루 횟수 필드 없음)도 그대로 읽어야 한다
	@Test
	void readsLegacyStateFileWithoutDailyCountFields(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("state.json");
		Files.writeString(file, "{\"consecutiveLosses\":2,\"paused\":false}");
		AutoSellGuard guard = guard(10, 3, file, dayClock("2026-09-21"));

		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordAttempt(true);
		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	// 저장에 실패하면 파일이 최신이 아니므로 주문을 막는다
	@Test
	void blocksWhenTheStateCannotBeSaved(@TempDir Path tempDir) throws IOException {
		Path notADirectory = tempDir.resolve("blocker");
		Files.writeString(notADirectory, "파일이라서 이 아래에는 디렉터리를 만들 수 없다");
		AutoSellGuard guard = guard(10, 3, notADirectory.resolve("state.json"), dayClock("2026-09-21"));

		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordAttempt(false);

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}
}
