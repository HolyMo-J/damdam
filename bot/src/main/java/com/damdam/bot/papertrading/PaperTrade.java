package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.time.LocalDate;

// 청산이 끝난 가상 거래 한 건 (docs/records.md 가상 계좌 기록의 원본)
public record PaperTrade(
	String strategy,
	String symbol,
	int rank,
	LocalDate signalDate,
	LocalDate entryDate,
	LocalDate exitDate,
	BigDecimal entryPrice,
	BigDecimal exitPrice,
	int quantity,
	ExitReason exitReason,
	// 비용(수수료, 세금) 반영 순손익(원)과 순손익률
	BigDecimal netProfit,
	BigDecimal netReturn,
	// 익절이나 손절이 갭으로 체결됨 (익절은 시가가 익절가 위, 손절은 시가가 손절 주문가 아래)
	boolean gapExit,
	boolean exitOnEntryDay,
	boolean entryClampedToHigh,
	boolean sellFailed,
	BigDecimal entryGapRate
) {

	public enum ExitReason {
		TAKE_PROFIT, STOP_LOSS, TIME_EXIT
	}
}
