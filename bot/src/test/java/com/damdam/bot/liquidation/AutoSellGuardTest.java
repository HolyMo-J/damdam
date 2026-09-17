package com.damdam.bot.liquidation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSellGuardTest {

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
}
