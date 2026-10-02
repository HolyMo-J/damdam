package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.StrategyOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.basis;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kst;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.row;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.scan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanRowLogTest {

	private static final Instant UPDATED = kst("2026-10-02T20:21:22");

	@TempDir
	Path dir;

	private SignalScan twoRows(Instant scannedAt) {
		return scan(scannedAt,
			row(1, "AAA", true, false, UPDATED, basis("100", "10000")),
			row(2, "BBB", false, false, null, null));
	}

	@Test
	void writesHeaderAndOneLinePerScannedSymbolIncludingNonSignaledOnes() throws IOException {
		Path file = dir.resolve("scan_rows.csv");
		int added = new ScanRowLog(file).append(twoRows(kst("2026-10-02T21:00:00")));

		List<String> lines = Files.readAllLines(file);
		assertEquals(2, added);
		assertEquals(3, lines.size());
		assertEquals(String.join(",", ScanRowLog.HEADER), lines.get(0));
		assertTrue(lines.get(1).startsWith(SIGNAL_DATE + ",AAA," + kst("2026-10-02T21:00:00") + ",1,EVALUATED,true,10000,9900,"));
		assertTrue(lines.get(1).contains("," + UPDATED + ",100,10000"));
		assertTrue(lines.get(2).startsWith(SIGNAL_DATE + ",BBB,"));
	}

	@Test
	void sameScanAppendedTwiceDoesNotDuplicateButARerunWithANewScanTimeAddsLines() throws IOException {
		Path file = dir.resolve("scan_rows.csv");
		ScanRowLog log = new ScanRowLog(file);

		log.append(twoRows(kst("2026-10-02T21:00:00")));
		int again = log.append(twoRows(kst("2026-10-02T21:00:00")));
		int rerun = log.append(twoRows(kst("2026-10-02T22:00:00")));

		assertEquals(0, again);
		assertEquals(2, rerun);
		assertEquals(5, Files.readAllLines(file).size());
	}

	@Test
	void unevaluatedRowsKeepTheirStatusAndLeaveEvidenceColumnsEmpty() throws IOException {
		SignalScan.Row failed = new SignalScan.Row(1, "CCC", "이름CCC",
			new StrategyOutcome<>(StrategyOutcome.Status.FETCH_FAILED, null, "HTTP 500, 재시도 실패"),
			new StrategyOutcome<>(StrategyOutcome.Status.INSUFFICIENT_CANDLES, null, "봉 부족"), null, null);
		Path file = dir.resolve("scan_rows.csv");

		new ScanRowLog(file).append(scan(kst("2026-10-02T21:00:00"), failed));

		String line = Files.readAllLines(file).get(1);
		// 상세 사유(detail)는 쉼표가 들어갈 수 있어서 이 파일에 넣지 않는다. 상태만 남고 근거 열은 비어 있다
		assertTrue(line.contains(",FETCH_FAILED,false,,,,,,,"));
		assertTrue(line.contains(",INSUFFICIENT_CANDLES,false,,,,,,"));
		assertEquals(ScanRowLog.HEADER.size(), line.split(",", -1).length);
	}
}
