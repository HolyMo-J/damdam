package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.damdam.bot.papertrading.PaperTestSupport.ENTRY_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperStateStoreTest {

	@TempDir
	Path dir;

	private PaperStateStore store() {
		return new PaperStateStore(dir.resolve("paper"));
	}

	private static PaperTrade trade() {
		return new PaperTrade("B", "005930", 3, SIGNAL_DATE, ENTRY_DATE, ENTRY_DATE.plusDays(1), bd("10000"), bd("10100"), 1,
			PaperTrade.ExitReason.TAKE_PROFIT, bd("54.5"), bd("0.005432"), true, false, true, false, bd("0.012345"));
	}

	@Test
	void missingFileMeansAFreshInitialState() {
		PaperStrategyState state = store().load("B");

		assertEquals(PaperStrategyState.initial("B"), state);
		assertEquals(null, state.settledThroughDate());
		assertEquals(List.of(), state.positions());
	}

	@Test
	void fullStateSurvivesASaveAndLoadRoundTripExactly() {
		PaperPosition held = PaperTestSupport.atBar(PaperTestSupport.position("005930", "10000", "100"), 2);
		PaperPosition lost = PaperTestSupport.position("000660", "200000", "1500");
		PaperStrategyState state = new PaperStrategyState("B", ENTRY_DATE, List.of(held),
			List.of(new PendingSignal("B", "035420", 7, ENTRY_DATE, bd("2500.5"), bd("180000"))),
			List.of(trade()),
			List.of(new PaperStrategyState.Abandoned(lost, ENTRY_DATE.plusDays(3))),
			Map.of("000660", 2), ENTRY_DATE.plusDays(2));

		store().save(state);
		PaperStrategyState loaded = store().load("B");

		// record의 equals가 BigDecimal의 자릿수(scale)까지 비교하므로 값과 자릿수가 모두 같아야 통과한다
		assertEquals(state, loaded);
		assertEquals(LocalDate.of(2026, 9, 29), loaded.settledThroughDate());
	}

	@Test
	void strategiesUseSeparateFiles() {
		store().save(PaperStrategyState.initial("A").settled(SIGNAL_DATE, List.of(), List.of()));

		assertEquals(null, store().load("B").settledThroughDate());
		assertEquals(SIGNAL_DATE, store().load("A").settledThroughDate());
	}

	@Test
	void unreadableFileFailsClosedInsteadOfResettingToInitial() throws IOException {
		store().save(PaperStrategyState.initial("B"));
		Path file = dir.resolve("paper/state_B.json");
		Files.writeString(file, "{ 깨진 json", StandardCharsets.UTF_8);

		assertThrows(IllegalStateException.class, () -> store().load("B"));

		// 읽지 못한 파일은 건드리지 않는다
		assertEquals("{ 깨진 json", Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void fileHoldingAnotherStrategyIsRejected() throws IOException {
		store().save(PaperStrategyState.initial("A"));
		Files.copy(dir.resolve("paper/state_A.json"), dir.resolve("paper/state_B.json"));

		assertThrows(IllegalStateException.class, () -> store().load("B"));
	}

	@Test
	void aLeftoverTempFileIsIgnoredAndOverwritten() throws IOException {
		Files.createDirectories(dir.resolve("paper"));
		Files.writeString(dir.resolve("paper/state_B.json.tmp"), "{ 쓰다 만", StandardCharsets.UTF_8);

		assertEquals(PaperStrategyState.initial("B"), store().load("B"));
		store().save(PaperStrategyState.initial("B").settled(SIGNAL_DATE, List.of(), List.of()));

		assertEquals(SIGNAL_DATE, store().load("B").settledThroughDate());
		assertFalse(Files.exists(dir.resolve("paper/state_B.json.tmp")));
	}

	@Test
	void savingKeepsTheLastConfirmedStateAsABackupCopy() throws IOException {
		PaperStrategyState first = PaperStrategyState.initial("B").settled(SIGNAL_DATE, List.of(), List.of());
		PaperStrategyState second = first.settled(ENTRY_DATE, List.of(), List.of());

		store().save(first);
		assertFalse(Files.exists(dir.resolve("paper/state_B.json.bak")));
		store().save(second);

		assertEquals(second, store().load("B"));
		// .bak은 직전 확정 상태다. 손상 때 사람이 복구용으로 쓰고, 자동으로 읽지는 않는다
		String backup = Files.readString(dir.resolve("paper/state_B.json.bak"), StandardCharsets.UTF_8);
		assertEquals(true, backup.contains("2026-09-28"), backup);
		assertEquals(false, backup.contains("2026-09-29"), backup);
	}

	@Test
	void rejectsStrategyNamesThatCouldEscapeTheDirectory() {
		assertThrows(IllegalArgumentException.class, () -> store().load("../B"));
		assertThrows(IllegalArgumentException.class, () -> store().load(""));
		assertThrows(IllegalArgumentException.class, () -> store().load(null));
		assertThrows(IllegalArgumentException.class, () -> store().load("B/../../x"));
	}

	@Test
	void amountsAreWrittenAsPlainNumbersAndDatesAsIsoStrings() throws IOException {
		BigDecimal price = new BigDecimal("1862000");
		PaperStrategyState state = PaperStrategyState.initial("B").settled(SIGNAL_DATE, List.of(), List.of(
			new PaperTrade("B", "000660", 1, SIGNAL_DATE, ENTRY_DATE, ENTRY_DATE, price, price, 1,
				PaperTrade.ExitReason.TIME_EXIT, bd("-1000"), bd("-0.0005"), false, false, false, false, bd("0"))));

		store().save(state);
		String json = Files.readString(dir.resolve("paper/state_B.json"), StandardCharsets.UTF_8);

		assertEquals(true, json.contains("\"2026-09-28\""), json);
		assertEquals(true, json.contains("1862000"), json);
		assertEquals(state, store().load("B"));
	}
}
