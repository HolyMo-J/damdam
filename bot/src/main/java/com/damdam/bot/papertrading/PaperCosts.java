package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 가상 거래의 비용 반영 손익. 수수료 0.015% 양방향, 매도 시 세금 0.20% (2026-09-28 현재 실제 요율, docs/strategy.md "공통 규칙").
// 실제 수수료는 원 단위로 끊기지만 가상매매는 반올림하지 않는다 (1주 거래에서 수 원 이하의 차이)
public final class PaperCosts {

	public static final BigDecimal FEE_RATE = new BigDecimal("0.00015");
	public static final BigDecimal SELL_TAX_RATE = new BigDecimal("0.0020");

	private PaperCosts() {
	}

	// 순손익(원) = 매도금액 x (1 - 수수료 - 세금) - 매수금액 x (1 + 수수료)
	public static BigDecimal netProfit(BigDecimal buyPrice, BigDecimal sellPrice, int quantity) {
		BigDecimal qty = BigDecimal.valueOf(quantity);
		BigDecimal sellNet = sellPrice.multiply(qty).multiply(BigDecimal.ONE.subtract(FEE_RATE).subtract(SELL_TAX_RATE));
		BigDecimal buyGross = buyPrice.multiply(qty).multiply(BigDecimal.ONE.add(FEE_RATE));
		return sellNet.subtract(buyGross);
	}

	// 거래별 순손익률 = 매도금액 x (1 - 수수료 - 세금) / (매수금액 x (1 + 수수료)) - 1. 2단계 engine.py의 net과 같은 정의다
	public static BigDecimal netReturn(BigDecimal buyPrice, BigDecimal sellPrice) {
		BigDecimal sellNet = sellPrice.multiply(BigDecimal.ONE.subtract(FEE_RATE).subtract(SELL_TAX_RATE));
		BigDecimal buyGross = buyPrice.multiply(BigDecimal.ONE.add(FEE_RATE));
		return sellNet.divide(buyGross, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
	}
}
