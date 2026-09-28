package com.damdam.bot.papertrading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 대상 종목군 전체에 대한 한 번의 신호 판정 결과. rows는 대상 종목군의 거래대금 순위 순서 그대로다
// (자금이 모자랄 때 "거래대금이 큰 순"으로 고르는 규칙에 그대로 쓰라고 순서를 지킨다).
// scannedAt은 이 판정을 실제로 돌린 시각이다. 신호일 당일 저녁 확정 전에 돌렸는지(잠정치로 났는지)를 나중에 알 수 있게 남긴다
public record SignalScan(LocalDate signalDate, Instant scannedAt, List<Row> rows) {

	public record Row(
		int rank,
		String symbol,
		String name,
		StrategyOutcome<IchimokuCloudBreakout.Result> ichimoku,
		StrategyOutcome<InstitutionNetBuySignal.Result> institution,
		// 신호일 기관 매매동향 기록의 마지막 갱신 시각. 기록을 못 받았거나 없으면 null. 확정치인지 판별할 수 있는지는 확인하지 못했으므로 판정에는 쓰지 않고 관찰용으로만 담는다
		Instant institutionRecordUpdatedAt
	) {
		// 판정을 끝냈고 신호가 난 경우만 true. 조회 실패나 데이터 오류는 신호가 아니다
		public boolean ichimokuSignaled() {
			return ichimoku.status() == StrategyOutcome.Status.EVALUATED && ichimoku.result().signaled();
		}

		public boolean institutionSignaled() {
			return institution.status() == StrategyOutcome.Status.EVALUATED && institution.result().signaled();
		}
	}

	// 호출부가 rows를 다시 세지 않아도 "신호 0건"과 "판정 불가"를 구분할 수 있게 전략별 상태 건수를 준다
	public long ichimokuCount(StrategyOutcome.Status status) {
		return rows.stream().filter(r -> r.ichimoku().status() == status).count();
	}

	public long institutionCount(StrategyOutcome.Status status) {
		return rows.stream().filter(r -> r.institution().status() == status).count();
	}
}
