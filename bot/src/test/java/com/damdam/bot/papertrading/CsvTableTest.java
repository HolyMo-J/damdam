package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvTableTest {

	private static final List<String> HEADER = List.of("strategy", "symbol", "signal_date", "note");

	@TempDir
	Path dir;

	private CsvTable table() {
		return new CsvTable(dir.resolve("out.csv"), HEADER, 3);
	}

	private List<String> lines() throws IOException {
		return Files.readAllLines(dir.resolve("out.csv"), StandardCharsets.UTF_8);
	}

	@Test
	void createsFileWithHeaderAndAppendsRows() throws IOException {
		int added = table().append(List.of(List.of("B", "005930", "2026-09-28", "x"), List.of("B", "000660", "2026-09-28", "y")));

		assertEquals(2, added);
		assertEquals(List.of("strategy,symbol,signal_date,note", "B,005930,2026-09-28,x", "B,000660,2026-09-28,y"), lines());
	}

	@Test
	void ignoresAnIdenticalRowThatIsAlreadyThere() throws IOException {
		table().append(List.of(List.of("B", "005930", "2026-09-28", "first")));

		int added = table().append(List.of(
			List.of("B", "005930", "2026-09-28", "first"),
			List.of("B", "005930", "2026-09-29", "other-date")));

		assertEquals(1, added);
		assertEquals(List.of("strategy,symbol,signal_date,note", "B,005930,2026-09-28,first", "B,005930,2026-09-29,other-date"),
			lines());
	}

	@Test
	void sameKeyWithDifferentContentFailsClosedAndLeavesTheFileUntouched() throws IOException {
		table().append(List.of(List.of("B", "005930", "2026-09-28", "first")));

		assertThrows(IllegalStateException.class, () -> table().append(List.of(
			List.of("B", "000660", "2026-09-28", "new-but-blocked-with-the-batch"),
			List.of("B", "005930", "2026-09-28", "second"))));

		// 배치 전체가 거부되어 앞의 새 행도 쓰이지 않는다
		assertEquals(List.of("strategy,symbol,signal_date,note", "B,005930,2026-09-28,first"), lines());
	}

	@Test
	void ignoresIdenticalDuplicatesInsideTheSameBatchButRejectsConflictingOnes() throws IOException {
		int added = table().append(List.of(
			List.of("B", "005930", "2026-09-28", "a"),
			List.of("B", "005930", "2026-09-28", "a")));

		assertEquals(1, added);
		assertEquals(2, lines().size());
		assertThrows(IllegalStateException.class, () -> new CsvTable(dir.resolve("other.csv"), HEADER, 3).append(List.of(
			List.of("B", "005930", "2026-09-28", "a"),
			List.of("B", "005930", "2026-09-28", "b"))));
	}

	@Test
	void doesNotRewriteTheFileWhenNothingIsNew() throws IOException {
		table().append(List.of(List.of("B", "005930", "2026-09-28", "a")));
		long modified = Files.getLastModifiedTime(dir.resolve("out.csv")).toMillis();

		assertEquals(0, table().append(List.of(List.of("B", "005930", "2026-09-28", "a"))));

		assertEquals(modified, Files.getLastModifiedTime(dir.resolve("out.csv")).toMillis());
		assertFalse(Files.exists(dir.resolve("out.csv.tmp")));
	}

	@Test
	void doesNotCreateAFileForAnEmptyBatch() {
		assertEquals(0, table().append(List.of()));
		assertFalse(Files.exists(dir.resolve("out.csv")));
	}

	@Test
	void refusesToOverwriteAFileWithADifferentHeader() throws IOException {
		Files.writeString(dir.resolve("out.csv"), "a,b,c,d\n1,2,3,4\n", StandardCharsets.UTF_8);

		assertThrows(IllegalStateException.class, () -> table().append(List.of(List.of("B", "005930", "2026-09-28", "x"))));

		assertEquals(List.of("a,b,c,d", "1,2,3,4"), lines());
	}

	@Test
	void acceptsAHeaderThatExcelSavedWithABom() throws IOException {
		Files.writeString(dir.resolve("out.csv"), "﻿strategy,symbol,signal_date,note\nB,005930,2026-09-28,a\n",
			StandardCharsets.UTF_8);

		int added = table().append(List.of(
			List.of("B", "005930", "2026-09-28", "a"),
			List.of("B", "000660", "2026-09-28", "new")));

		assertEquals(1, added);
		assertEquals(List.of("strategy,symbol,signal_date,note", "B,005930,2026-09-28,a", "B,000660,2026-09-28,new"), lines());
	}

	@Test
	void quotesNonKeyFieldsThatContainCommasOrQuotes() throws IOException {
		table().append(List.of(List.of("B", "005930", "2026-09-28", "a,\"b\"")));

		assertEquals("B,005930,2026-09-28,\"a,\"\"b\"\"\"", lines().get(1));
		// 따옴표가 든 행도 다음 실행에서 같은 키로 알아본다
		assertEquals(0, table().append(List.of(List.of("B", "005930", "2026-09-28", "a,\"b\""))));
	}

	@Test
	void rejectsKeyFieldsWithSeparatorsAndWrongColumnCounts() {
		assertThrows(IllegalArgumentException.class, () -> table().append(List.of(List.of("B", "00,5930", "2026-09-28", "x"))));
		assertThrows(IllegalArgumentException.class, () -> table().append(List.of(List.of("B", "005930", "2026-09-28"))));
	}

	@Test
	void aLeftoverTempFileFromACrashDoesNotBreakTheNextWrite() throws IOException {
		Files.writeString(dir.resolve("out.csv.tmp"), "garbage", StandardCharsets.UTF_8);

		table().append(List.of(List.of("B", "005930", "2026-09-28", "a")));

		assertEquals(List.of("strategy,symbol,signal_date,note", "B,005930,2026-09-28,a"), lines());
		assertFalse(Files.exists(dir.resolve("out.csv.tmp")));
	}
}
