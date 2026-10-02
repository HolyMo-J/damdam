package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.PendingSignal;
import com.damdam.bot.papertrading.SignalScan;

import java.util.ArrayList;
import java.util.List;

// 스캔 결과에서 한 전략의 신호가 난 종목을 다음 거래일 진입 대기 신호로 바꾼다. 순서는 스캔 행 순서(거래대금 순위 순)를 그대로 지킨다
final class PendingSignals {

	// missingBasis: 신호가 났지만 신호일 ATR과 종가를 계산하지 못해 대기 신호를 만들 수 없는 종목. 조용히 빼지 않고 알린다
	record Built(List<PendingSignal> signals, List<String> missingBasis) {
	}

	private PendingSignals() {
	}

	static Built build(String strategy, SignalScan scan) {
		// 스캔 행이 비어 있어도 잘못된 전략 이름은 막는다 (행마다 검사하면 빈 스캔에서는 오타가 조용히 지나간다)
		if (!Strategies.ALL.contains(strategy)) {
			throw new IllegalArgumentException("알 수 없는 전략입니다: " + strategy);
		}
		List<PendingSignal> signals = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (SignalScan.Row row : scan.rows()) {
			boolean signaled = switch (strategy) {
				case Strategies.ICHIMOKU -> row.ichimokuSignaled();
				case Strategies.INSTITUTION -> row.institutionSignaled();
				default -> throw new IllegalArgumentException("알 수 없는 전략입니다: " + strategy);
			};
			if (!signaled) {
				continue;
			}
			SignalScan.EntryBasis basis = row.entryBasis();
			if (basis == null) {
				missing.add(row.symbol());
				continue;
			}
			signals.add(new PendingSignal(strategy, row.symbol(), row.rank(), scan.signalDate(), basis.atr(), basis.signalClose()));
		}
		return new Built(List.copyOf(signals), List.copyOf(missing));
	}
}
