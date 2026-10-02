package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.CsvTable;
import com.damdam.bot.papertrading.IchimokuCloudBreakout;
import com.damdam.bot.papertrading.InstitutionNetBuySignal;
import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.StrategyOutcome;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 신호 판정 전체 행(대상 종목군 모두, 신호가 안 난 종목 포함)을 scan_rows.csv에 남긴다 (docs/records.md "신호 기록에 남길 값").
// 판정 상태를 함께 남겨야 "신호 없음"과 "조회 실패로 판정 못 함"을 나중에 구분하고, 판정 시각과 갱신 시각으로 잠정치로 신호가 났는지
// 사후에 확인할 수 있다. 키는 (신호일, 종목, 판정 시각)이라서 같은 신호일을 다시 돌리면 줄이 더해지고 두 번째 실행이 첫 실행을 덮어쓰거나
// 예외를 내지 않는다 (실행마다 값이 달라질 수 있다는 것 자체가 확인할 대상이다)
final class ScanRowLog {

	static final List<String> HEADER = List.of(
		"signal_date", "symbol", "scanned_at", "rank",
		"b_status", "b_signaled", "b_close_today", "b_cloud_top_today", "b_close_previous", "b_cloud_top_previous",
		"b_volume_today", "b_average_volume",
		"a_status", "a_signaled", "a_net_buy_sum", "a_volume_sum", "a_ratio_percent", "a_record_updated_at",
		"atr", "signal_close");
	private static final int KEY_COLUMNS = 3;

	private final CsvTable table;

	ScanRowLog(Path file) {
		this.table = new CsvTable(file, HEADER, KEY_COLUMNS);
	}

	// 파일에 실제로 더한 행 수를 돌려준다
	int append(SignalScan scan) {
		List<List<String>> rows = new ArrayList<>();
		for (SignalScan.Row row : scan.rows()) {
			rows.add(toRow(scan, row));
		}
		return table.append(rows);
	}

	static List<String> toRow(SignalScan scan, SignalScan.Row row) {
		StrategyOutcome<IchimokuCloudBreakout.Result> b = row.ichimoku();
		StrategyOutcome<InstitutionNetBuySignal.Result> a = row.institution();
		IchimokuCloudBreakout.Result br = b.result();
		InstitutionNetBuySignal.Result ar = a.result();
		SignalScan.EntryBasis basis = row.entryBasis();
		List<String> cells = new ArrayList<>();
		cells.add(scan.signalDate().toString());
		cells.add(row.symbol());
		cells.add(scan.scannedAt().toString());
		cells.add(Integer.toString(row.rank()));
		cells.add(b.status().name());
		cells.add(Boolean.toString(row.ichimokuSignaled()));
		cells.add(br == null ? "" : plain(br.closeToday()));
		cells.add(br == null ? "" : plain(br.cloudTopToday()));
		cells.add(br == null ? "" : plain(br.closePrevious()));
		cells.add(br == null ? "" : plain(br.cloudTopPrevious()));
		cells.add(br == null ? "" : plain(br.volumeToday()));
		cells.add(br == null ? "" : plain(br.averageVolume()));
		cells.add(a.status().name());
		cells.add(Boolean.toString(row.institutionSignaled()));
		cells.add(ar == null ? "" : plain(ar.netBuySum()));
		cells.add(ar == null ? "" : plain(ar.volumeSum()));
		cells.add(ar == null ? "" : plain(ar.ratioPercent()));
		cells.add(row.institutionRecordUpdatedAt() == null ? "" : row.institutionRecordUpdatedAt().toString());
		cells.add(basis == null ? "" : plain(basis.atr()));
		cells.add(basis == null ? "" : plain(basis.signalClose()));
		return cells;
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.toPlainString();
	}
}
