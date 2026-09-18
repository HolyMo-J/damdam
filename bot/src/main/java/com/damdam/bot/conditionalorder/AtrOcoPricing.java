package com.damdam.bot.conditionalorder;

import java.math.BigDecimal;
import java.math.RoundingMode;

// ATR 익절/손절(docs/strategy.md v0) 가격을 계산한다.
// 호가 단위(tick size) 구간표는 토스 공식 문서가 예시만 줄 뿐 전체를 제공하지 않아 추측해서 보정하지 않는다.
// KR은 정수로, US는 문서에 명시된 $1 기준 소수 자리수로만 맞춘다. 실제 호가 단위와 안 맞으면
// API가 400으로 거부하며 올바른 tickSize/nearestPrices를 응답에 담아주므로, 그 로그를 보고 대응한다.
public final class AtrOcoPricing {

	private AtrOcoPricing() {
	}

	public record Prices(String takeProfitTrigger, String takeProfitOrderPrice,
			String stopLossTrigger, String stopLossOrderPrice) {
	}

	// entryPrice: 매수 체결가, atr14: 최근 14거래일 ATR
	public static Prices calculate(BigDecimal entryPrice, BigDecimal atr14, boolean isKrw) {
		BigDecimal takeProfit = round(entryPrice.add(atr14), isKrw);
		BigDecimal stopLoss = round(entryPrice.subtract(atr14), isKrw);
		BigDecimal stopLossOrderPrice = round(stopLoss.subtract(minimumStep(stopLoss, isKrw)), isKrw);

		String takeProfitPrice = takeProfit.toPlainString();
		return new Prices(takeProfitPrice, takeProfitPrice, stopLoss.toPlainString(), stopLossOrderPrice.toPlainString());
	}

	// 손절 지정가는 확실한 체결을 위해 감시가보다 한 스텝 낮게 건다
	private static BigDecimal minimumStep(BigDecimal price, boolean isKrw) {
		if (isKrw) {
			return BigDecimal.ONE;
		}
		return price.compareTo(BigDecimal.ONE) < 0 ? new BigDecimal("0.0001") : new BigDecimal("0.01");
	}

	private static BigDecimal round(BigDecimal price, boolean isKrw) {
		if (isKrw) {
			return price.setScale(0, RoundingMode.HALF_UP);
		}
		int scale = price.compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
		return price.setScale(scale, RoundingMode.DOWN);
	}
}
