package com.damdam.bot.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// 진짜 변동폭(True Range)의 단순 평균. docs/strategy.md v0: 최근 14거래일 ATR
public final class AverageTrueRange {

	private AverageTrueRange() {
	}

	// candlesNewestFirst는 최신순(timestamp 내림차순)이어야 하며, 전일 종가 비교를 위해 period + 1개 이상 필요하다
	public static BigDecimal calculate(List<Candle> candlesNewestFirst, int period) {
		if (candlesNewestFirst.size() < period + 1) {
			throw new IllegalArgumentException(
				"ATR(%d) 계산에는 캔들이 최소 %d개 필요한데 %d개만 있습니다.".formatted(period, period + 1, candlesNewestFirst.size()));
		}

		BigDecimal sum = BigDecimal.ZERO;
		for (int i = 0; i < period; i++) {
			sum = sum.add(trueRange(candlesNewestFirst.get(i), candlesNewestFirst.get(i + 1)));
		}

		return sum.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);
	}

	private static BigDecimal trueRange(Candle current, Candle previous) {
		BigDecimal high = new BigDecimal(current.highPrice());
		BigDecimal low = new BigDecimal(current.lowPrice());
		BigDecimal previousClose = new BigDecimal(previous.closePrice());

		BigDecimal highLow = high.subtract(low);
		BigDecimal highPrevClose = high.subtract(previousClose).abs();
		BigDecimal lowPrevClose = low.subtract(previousClose).abs();

		return highLow.max(highPrevClose).max(lowPrevClose);
	}
}
