package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;

// 주간 손실 한도: 전략별 가상 자금(50만원)의 10%를 그 주(월~금) 실현 순손실이 넘으면 그 주는 새 가상 매수를 멈춘다 (청산은 계속).
// 실전 자금 구간별 기준과 별개인 고정값이다 (docs/strategy.md "공통 규칙", 2026-09-28)
public final class WeeklyLossLimit {

	public static final BigDecimal VIRTUAL_CAPITAL = new BigDecimal("500000");
	public static final BigDecimal LIMIT_RATE = new BigDecimal("0.10");
	public static final BigDecimal LIMIT = VIRTUAL_CAPITAL.multiply(LIMIT_RATE);

	private WeeklyLossLimit() {
	}

	public static LocalDate weekStart(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	// 실현 손익은 청산일이 속한 주로 세고, 정지 여부는 진입일이 속한 주 기준으로 판정한다.
	// 진입은 그날 청산보다 먼저 처리하므로(PaperDaySettlement) 진입일 당일 청산분은 세지 않는다.
	// 그 주 실현 순손익(이익과 손실의 합)이 -한도보다 나쁘면 정지다. 정확히 한도와 같으면 "넘은" 것이 아니라 정지하지 않는다
	public static boolean isHalted(LocalDate entryDate, Collection<PaperTrade> closedTrades) {
		LocalDate weekStart = weekStart(entryDate);
		BigDecimal net = closedTrades.stream()
			.filter(t -> !t.exitDate().isBefore(weekStart) && t.exitDate().isBefore(entryDate))
			.map(PaperTrade::netProfit)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return net.negate().compareTo(LIMIT) > 0;
	}
}
