package com.damdam.bot.liquidation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSellGuardTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private static Clock dayClock(String isoDate) {
		return Clock.fixed(Instant.parse(isoDate + "T03:00:00Z"), ZONE);
	}

	// 보낸 알림을 모아 두는 가짜 Notifier (실제 디스코드로는 아무것도 나가지 않는다)
	private final List<String> alerts = new ArrayList<>();

	private AutoSellGuard guard(int maxDaily, int maxLosses, Path file, Clock clock) {
		return new AutoSellGuard(maxDaily, maxLosses, file.toString(), clock, (key, message) -> alerts.add(key));
	}

	@Test
	void blocksAfterDailyCountLimit(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(2, 10, tempDir.resolve("state.json"), Clock.systemDefaultZone());

		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordDailyAttempt();
		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordDailyAttempt();

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void pausesAfterConsecutiveLosses(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(100, 3, tempDir.resolve("state.json"), Clock.systemDefaultZone());

		guard.recordSellResult(true);
		guard.recordSellResult(true);
		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordSellResult(true);

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void aWinResetsConsecutiveLosses(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(100, 2, tempDir.resolve("state.json"), Clock.systemDefaultZone());

		guard.recordSellResult(true);
		guard.recordSellResult(false);
		guard.recordSellResult(true);

		assertTrue(guard.canPlaceAutoSell("AAA"));
	}

	// 하루 횟수와 연속 손실은 서로 다른 시점(접수/체결)에 갱신되므로 한쪽이 다른 쪽에 영향을 주면 안 된다
	@Test
	void dailyAttemptsDoNotAffectConsecutiveLosses(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(100, 1, tempDir.resolve("state.json"), Clock.systemDefaultZone());

		guard.recordDailyAttempt();
		guard.recordDailyAttempt();
		guard.recordDailyAttempt();

		assertTrue(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void sellResultsDoNotAffectDailyCount(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(1, 100, tempDir.resolve("state.json"), Clock.systemDefaultZone());

		guard.recordSellResult(false);
		guard.recordSellResult(false);
		guard.recordSellResult(false);

		assertTrue(guard.canPlaceAutoSell("AAA"));
	}

	// 재시작해도 하루 횟수가 0으로 돌아가면 안 된다 (재시작으로 한도를 우회하는 경로 차단)
	@Test
	void dailyCountSurvivesRestart(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");
		Clock today = dayClock("2026-09-21");

		AutoSellGuard first = guard(2, 10, file, today);
		first.recordDailyAttempt();
		first.recordDailyAttempt();

		AutoSellGuard restarted = guard(2, 10, file, today);
		assertFalse(restarted.canPlaceAutoSell("AAA"));
	}

	@Test
	void dailyCountResetsOnANewDay(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");

		AutoSellGuard monday = guard(1, 10, file, dayClock("2026-09-21"));
		monday.recordDailyAttempt();
		assertFalse(monday.canPlaceAutoSell("AAA"));

		AutoSellGuard tuesday = guard(1, 10, file, dayClock("2026-09-22"));
		assertTrue(tuesday.canPlaceAutoSell("AAA"));
	}

	@Test
	void pausedStateSurvivesRestart(@TempDir Path tempDir) {
		Path file = tempDir.resolve("state.json");
		Clock today = dayClock("2026-09-21");

		AutoSellGuard first = guard(100, 2, file, today);
		first.recordSellResult(true);
		first.recordSellResult(true);

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
		guard.recordSellResult(true);
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
		guard.recordSellResult(true);
		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	// 저장에 실패하면 파일이 최신이 아니므로 주문을 막는다
	@Test
	void blocksWhenTheStateCannotBeSaved(@TempDir Path tempDir) throws IOException {
		Path notADirectory = tempDir.resolve("blocker");
		Files.writeString(notADirectory, "파일이라서 이 아래에는 디렉터리를 만들 수 없다");
		AutoSellGuard guard = guard(10, 3, notADirectory.resolve("state.json"), dayClock("2026-09-21"));

		assertTrue(guard.canPlaceAutoSell("AAA"));
		guard.recordDailyAttempt();

		assertFalse(guard.canPlaceAutoSell("AAA"));
	}

	@Test
	void alertsWhenTheConsecutiveLossLimitPausesAutoSell(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(100, 2, tempDir.resolve("state.json"), dayClock("2026-09-21"));

		guard.recordSellResult(true);
		assertTrue(alerts.isEmpty());
		guard.recordSellResult(true);

		assertEquals(List.of("guard-paused-now"), alerts);
	}

	@Test
	void alertsWhenTheStateFileIsUnreadable(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("state.json");
		Files.writeString(file, "{ 깨진 파일");

		guard(10, 3, file, dayClock("2026-09-21")).canPlaceAutoSell("AAA");

		assertTrue(alerts.contains("guard-unreadable"));
		assertTrue(alerts.contains("guard-paused"));
	}

	@Test
	void alertsWhenTheDailyLimitBlocksAnOrder(@TempDir Path tempDir) {
		AutoSellGuard guard = guard(1, 10, tempDir.resolve("state.json"), dayClock("2026-09-21"));
		guard.recordDailyAttempt();

		guard.canPlaceAutoSell("AAA");

		assertEquals(List.of("guard-daily-limit"), alerts);
	}
}
