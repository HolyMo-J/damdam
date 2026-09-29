package com.damdam.bot.papertrading;

import java.math.BigDecimal;

final class Slippage {

	private Slippage() {
	}

	// 0 이상 1 미만만 허용한다. 음수나 1 이상은 가격이 뒤집히므로 잘못 넘긴 값이다
	static BigDecimal require(BigDecimal rate) {
		if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
			throw new IllegalArgumentException("슬리피지는 0 이상 1 미만이어야 합니다: " + rate);
		}
		return rate;
	}
}
