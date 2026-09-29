package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.time.LocalDate;

// 진입해서 보유 중인 가상 포지션 (불변). 봉 하나를 처리할 때마다 barsProcessed가 1 늘어난 새 값을 만든다.
// barsProcessed: 지금까지 청산 판정을 마친 봉 수. 0이면 다음에 처리할 봉이 진입 봉이고, 5 이상이면 시간 청산 봉이다
public record PaperPosition(
	String strategy,
	String symbol,
	int rank,
	LocalDate signalDate,
	LocalDate entryDate,
	BigDecimal entryPrice,
	BigDecimal takeProfitPrice,
	BigDecimal stopTrigger,
	BigDecimal stopOrderPrice,
	BigDecimal atr,
	BigDecimal signalClose,
	// 진입 봉 종가. 수정주가 보정 비율(새 종가 / 저장한 종가)을 구하는 기준이다 (보정 자체는 상태 저장 단계에서 한다)
	BigDecimal entryBarClose,
	int quantity,
	int barsProcessed,
	// 마지막으로 청산 판정을 마친 봉의 날짜. 아직 판정 전(진입 직후)이면 null. 같은 봉을 두 번 처리하는 것을 막는 근거다
	LocalDate lastBarDate,
	// 손절이 발동했는데 체결되지 못해 이월된 포지션. 이후에는 익절과 손절을 판정하지 않고 시간 청산에 고정한다
	boolean sellFailed,
	boolean entryClampedToHigh,
	// (진입 봉 시가 - 신호일 종가) / 신호일 종가
	BigDecimal entryGapRate
) {

	PaperPosition advanced(LocalDate barDate, boolean sellFailedNow) {
		return new PaperPosition(strategy, symbol, rank, signalDate, entryDate, entryPrice, takeProfitPrice,
			stopTrigger, stopOrderPrice, atr, signalClose, entryBarClose, quantity, barsProcessed + 1, barDate,
			sellFailedNow, entryClampedToHigh, entryGapRate);
	}

	BigDecimal exposure() {
		return entryPrice.multiply(BigDecimal.valueOf(quantity));
	}
}
