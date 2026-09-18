package com.damdam.bot.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingHaltSwitchTest {

	private final List<String> alerts = new ArrayList<>();

	private TradingHaltSwitch haltSwitch(Path file) {
		return new TradingHaltSwitch(file.toString(), (key, message) -> alerts.add(key));
	}

	@Test
	void isNotHaltedWithoutTheFile(@TempDir Path tempDir) {
		assertFalse(haltSwitch(tempDir.resolve("STOP")).isHalted());
		assertTrue(alerts.isEmpty());
	}

	// 재시작 없이, 파일을 만들면 바로 멈추고 지우면 바로 재개된다
	@Test
	void followsTheFileWithoutRestart(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("STOP");
		TradingHaltSwitch halt = haltSwitch(file);
		assertFalse(halt.isHalted());

		Files.writeString(file, "");
		assertTrue(halt.isHalted());

		Files.delete(file);
		assertFalse(halt.isHalted());
	}

	// 상태가 바뀔 때만 한 번씩 알린다 (확인할 때마다 알림이 나가면 안 된다)
	@Test
	void alertsOnlyOnTransitions(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("STOP");
		TradingHaltSwitch halt = haltSwitch(file);

		Files.writeString(file, "");
		halt.isHalted();
		halt.isHalted();
		halt.isHalted();
		assertEquals(List.of("halt-on"), alerts);

		Files.delete(file);
		halt.isHalted();
		halt.isHalted();
		assertEquals(List.of("halt-on", "halt-off"), alerts);
	}
}
