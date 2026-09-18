package com.damdam.bot.records;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeRecordWriterTest {

	private static boolean record(TradeRecordWriter writer, String orderId, String filledQuantity, String source) {
		return writer.record(orderId, "AAA", "BUY", "FILL", filledQuantity, "100", "1000", "1", "0", "USD", "LIMIT",
			"FILLED", "resync".equals(source) ? "2026-09-21T10:00:00+09:00" : null, source);
	}

	@Test
	void writesTheNewHeaderAndRow(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("trades.csv");

		assertTrue(record(new TradeRecordWriter(file.toString()), "o-1", "10", TradeRecordWriter.SOURCE_STREAM));

		List<String> lines = Files.readAllLines(file);
		assertTrue(lines.get(0).endsWith(",status,filled_at,source"));
		assertEquals(2, lines.size());
		assertTrue(lines.get(1).endsWith(",FILLED,,stream"));
	}

	// 같은 체결이 웹소켓과 재동기화 양쪽에서 들어와도 한 번만 기록된다
	@Test
	void doesNotRecordTheSameFillTwice(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("trades.csv");
		TradeRecordWriter writer = new TradeRecordWriter(file.toString());

		assertTrue(record(writer, "o-1", "10", TradeRecordWriter.SOURCE_STREAM));
		assertFalse(record(writer, "o-1", "10", TradeRecordWriter.SOURCE_RESYNC));

		assertEquals(2, Files.readAllLines(file).size());
	}

	// 부분 체결 뒤 최종 체결은 누적 수량이 달라서 별도 행으로 남는다
	@Test
	void recordsDifferentCumulativeQuantitiesOfTheSameOrder(@TempDir Path tempDir) throws IOException {
		TradeRecordWriter writer = new TradeRecordWriter(tempDir.resolve("trades.csv").toString());

		assertTrue(record(writer, "o-1", "3", TradeRecordWriter.SOURCE_STREAM));
		assertTrue(record(writer, "o-1", "10", TradeRecordWriter.SOURCE_RESYNC));
	}

	// 웹소켓은 "10", REST는 "10.000"처럼 표기가 달라도 같은 체결로 본다
	@Test
	void treatsDifferentNumberFormatsAsTheSameFill(@TempDir Path tempDir) {
		TradeRecordWriter writer = new TradeRecordWriter(tempDir.resolve("trades.csv").toString());

		assertTrue(record(writer, "o-1", "10", TradeRecordWriter.SOURCE_STREAM));
		assertFalse(record(writer, "o-1", "10.000", TradeRecordWriter.SOURCE_RESYNC));
	}

	// 봇을 재시작해도 파일에 이미 있는 체결은 다시 기록하지 않는다
	@Test
	void remembersExistingRowsAfterARestart(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("trades.csv");
		record(new TradeRecordWriter(file.toString()), "o-1", "10", TradeRecordWriter.SOURCE_STREAM);

		TradeRecordWriter restarted = new TradeRecordWriter(file.toString());

		assertFalse(record(restarted, "o-1", "10", TradeRecordWriter.SOURCE_RESYNC));
		assertTrue(record(restarted, "o-2", "5", TradeRecordWriter.SOURCE_RESYNC));
		assertEquals(3, Files.readAllLines(file).size());
	}

	// 옛 형식(컬럼 13개) 파일은 기존 행을 유지한 채 새 컬럼이 붙고, 옛 행도 중복 검사에 쓰인다
	@Test
	void migratesTheLegacyFileKeepingOldRows(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("trades.csv");
		Files.writeString(file, "timestamp,order_id,symbol,side,event,filled_quantity,average_filled_price,filled_amount,"
			+ "commission,tax,currency,order_type,status\n"
			+ "2026-09-18T07:00:00Z,old-1,AAA,BUY,FILL,10,100,1000,1,0,USD,LIMIT,FILLED\n");
		TradeRecordWriter writer = new TradeRecordWriter(file.toString());

		assertFalse(record(writer, "old-1", "10", TradeRecordWriter.SOURCE_RESYNC));
		assertTrue(record(writer, "new-1", "5", TradeRecordWriter.SOURCE_STREAM));

		List<String> lines = Files.readAllLines(file);
		assertEquals(3, lines.size());
		assertTrue(lines.get(0).endsWith(",status,filled_at,source"));
		assertEquals("2026-09-18T07:00:00Z,old-1,AAA,BUY,FILL,10,100,1000,1,0,USD,LIMIT,FILLED,,", lines.get(1));
		lines.forEach(line -> assertEquals(15, line.split(",", -1).length, line));
	}
}
