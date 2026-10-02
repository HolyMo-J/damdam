package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.PaperLedger;
import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.StrategyOutcome;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

// 한 번 실행의 결과를 디스코드와 로그에 보낼 문장으로 만든다 (순수 함수). 정상이어도 하루 한 줄 요약을 보내서
// 이 러너가 돌았는지 사용자가 알 수 있게 하고, 확인이 필요한 일이 있으면 맨 앞에 [주의]를 붙인다
final class RunReport {

	// settlement: 이 전략의 정산 결과 (정산을 돌리지 못했으면 null)
	// gapReason: 대기 신호를 저장하지 못해 그날을 공백으로 남겼다면 그 사유, 저장했으면 null
	// pendingCount: 저장한 대기 신호 수, missingBasis: 신호가 났지만 ATR과 종가를 몰라 못 만든 종목
	// alreadySaved: 같은 신호일의 대기 신호가 이미 저장돼 있어서 이번 실행은 다시 판정하지 않았다 (pendingCount는 이미 저장된 건수)
	record StrategyResult(String strategy, PaperLedger.Report settlement, String gapReason, int pendingCount,
			List<String> missingBasis, boolean alreadySaved) {

		boolean needsAttention() {
			boolean settlementProblem = settlement != null && (!settlement.complete() || settlement.systemicMiss()
				|| !settlement.abandonedSymbols().isEmpty());
			return settlementProblem || gapReason != null || !missingBasis.isEmpty();
		}
	}

	record Message(boolean attention, String text) {
	}

	private RunReport() {
	}

	// scan은 신호 판정을 돌리지 못했으면 null이다. warnings는 신호 저장과 별개로 알려야 할 문제(기록 파일 쓰기 실패 등)다.
	// 스캔에서 일봉이나 매매동향 조회가 끝내 실패한 종목이 하나라도 있으면 그 종목의 신호는 소실된 것이라 [주의]로 알린다
	// (판정 종목이 절반 미만이면 스캔 자체가 예외라 여기까지 오지 않지만, 50~100%일 때는 숫자만 보이면 놓치기 쉽다)
	static Message format(LocalDate signalDate, List<StrategyResult> results, SignalScan scan, List<String> warnings) {
		boolean fetchFailures = scan != null && (scan.ichimokuCount(StrategyOutcome.Status.FETCH_FAILED) > 0
			|| scan.institutionCount(StrategyOutcome.Status.FETCH_FAILED) > 0);
		boolean attention = !warnings.isEmpty() || fetchFailures || results.stream().anyMatch(StrategyResult::needsAttention);
		List<String> lines = new ArrayList<>();
		lines.add((attention ? "[주의] " : "") + "가상매매 실행 결과 (신호일 " + signalDate + ")");
		if (scan != null) {
			lines.add("신호 판정: 종목 " + scan.rows().size() + "개, 전략 B " + summarize(scan.ichimokuCount(StrategyOutcome.Status.EVALUATED),
				scan.ichimokuCount(StrategyOutcome.Status.INSUFFICIENT_CANDLES), scan.ichimokuCount(StrategyOutcome.Status.DATA_ERROR),
				scan.ichimokuCount(StrategyOutcome.Status.FETCH_FAILED)) + ", 전략 A " + summarize(
				scan.institutionCount(StrategyOutcome.Status.EVALUATED), scan.institutionCount(StrategyOutcome.Status.INSUFFICIENT_CANDLES),
				scan.institutionCount(StrategyOutcome.Status.DATA_ERROR), scan.institutionCount(StrategyOutcome.Status.FETCH_FAILED)));
		}
		for (StrategyResult result : results) {
			lines.add(describe(result));
		}
		warnings.forEach(warning -> lines.add("경고: " + warning));
		return new Message(attention, String.join("\n", lines));
	}

	private static String summarize(long evaluated, long insufficient, long dataError, long fetchFailed) {
		return "판정 %d, 봉 부족 %d, 데이터 오류 %d, 조회 실패 %d".formatted(evaluated, insufficient, dataError, fetchFailed);
	}

	private static String describe(StrategyResult result) {
		StringBuilder line = new StringBuilder("전략 ").append(result.strategy()).append(": ");
		PaperLedger.Report settlement = result.settlement();
		if (settlement == null) {
			line.append("정산하지 못함");
		} else {
			line.append("정산 ").append(settlement.settledDates().size()).append("거래일");
			if (!settlement.complete()) {
				line.append(" (미확정 종목 ").append(String.join(" ", settlement.unsettledSymbols())).append(')');
			}
			if (settlement.systemicMiss()) {
				line.append(" (봉 조회 전체 결측으로 멈춤)");
			}
			if (!settlement.abandonedSymbols().isEmpty()) {
				line.append(" (이번에 포기한 종목 ").append(String.join(" ", settlement.abandonedSymbols())).append(')');
			}
		}
		if (result.gapReason() != null) {
			line.append(", 신호 공백: ").append(result.gapReason());
		} else if (result.alreadySaved()) {
			line.append(", 이미 저장된 대기 신호 ").append(result.pendingCount()).append("건이 있어 다시 판정하지 않음");
		} else {
			line.append(", 대기 신호 ").append(result.pendingCount()).append("건 저장");
		}
		if (!result.missingBasis().isEmpty()) {
			line.append(", ATR이나 종가를 몰라 대기 신호를 만들지 못한 종목 ").append(String.join(" ", result.missingBasis()));
		}
		return line.toString();
	}
}
