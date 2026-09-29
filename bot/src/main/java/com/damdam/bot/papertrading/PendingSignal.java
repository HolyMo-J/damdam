package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

// 신호일 저녁에 만들어 두었다가 다음 거래일 봉으로 진입 처리하는 대기 신호 (docs/strategy.md "실행 방식").
// 슬리피지를 바꿔 같은 흐름을 다시 돌릴 수 있게 순위와 신호일 ATR과 종가를 함께 담는다
public record PendingSignal(String strategy, String symbol, int rank, LocalDate signalDate,
		BigDecimal atr, BigDecimal signalClose) {

	public PendingSignal {
		Objects.requireNonNull(strategy);
		Objects.requireNonNull(symbol);
		Objects.requireNonNull(signalDate);
		Objects.requireNonNull(atr);
		Objects.requireNonNull(signalClose);
	}
}
