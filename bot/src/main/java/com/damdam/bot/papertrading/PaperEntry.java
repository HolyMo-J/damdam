package com.damdam.bot.papertrading;

import com.damdam.bot.market.AtrOcoPricing;
import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

// 대기 신호를 진입 봉(신호 다음 거래일 봉)으로 진입 처리하는 순수 함수 (docs/strategy.md "실행 방식" 가상 체결 근사).
// 총 노출 한도, 주간 손실 한도는 여기서 보지 않는다 (ExposureLimit, WeeklyLossLimit, PaperDaySettlement)
public final class PaperEntry {

	// 신호 한 건당 1주씩 산다 (2026-09-29 사용자 결정, 4단계와 같은 규칙)
	public static final int QUANTITY = 1;

	private PaperEntry() {
	}

	public sealed interface Result permits Entered, Skipped {
	}

	public record Entered(PaperPosition position) implements Result {
	}

	public record Skipped(SkipReason reason) implements Result {
	}

	// entryBar는 신호 다음 거래일의 일봉이고 date는 그 봉의 날짜다. 국내 종목(원 단위 정수 가격)만 다룬다
	public static Result enter(PendingSignal signal, LocalDate date, Candle entryBar, BigDecimal slippage) {
		Slippage.require(slippage);
		requireBarDate(entryBar, date);
		if (!date.isAfter(signal.signalDate())) {
			throw new IllegalArgumentException("진입일(%s)은 신호일(%s)보다 뒤여야 합니다.".formatted(date, signal.signalDate()));
		}

		BigDecimal open = new BigDecimal(entryBar.openPrice());
		BigDecimal high = new BigDecimal(entryBar.highPrice());
		BigDecimal volume = new BigDecimal(entryBar.volume());
		if (volume.signum() == 0) {
			return new Skipped(SkipReason.ZERO_VOLUME);
		}

		// 시가 x (1 + 슬리피지)를 원 단위로 반올림한다. 그날 고가보다 높으면 진입 실패가 아니라 고가로 제한한다
		// (실패로 두면 시가 뒤 하락만 한 손실 거래가 빠져 낙관 편향이 생긴다)
		BigDecimal entryPrice = open.multiply(BigDecimal.ONE.add(slippage)).setScale(0, RoundingMode.HALF_UP);
		boolean clamped = entryPrice.compareTo(high) > 0;
		if (clamped) {
			entryPrice = high;
		}

		// 진입가 - ATR이 0 이하면 손절가가 없어진다. ATR이 반올림해서 0.5 미만이면 익절가나 손절가가 진입가와 같아진다
		if (entryPrice.subtract(signal.atr()).signum() <= 0) {
			return new Skipped(SkipReason.INVALID_EXIT_PRICES);
		}
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(entryPrice, signal.atr(), true);
		BigDecimal takeProfit = new BigDecimal(prices.takeProfitOrderPrice());
		BigDecimal stopTrigger = new BigDecimal(prices.stopLossTrigger());
		BigDecimal stopOrder = new BigDecimal(prices.stopLossOrderPrice());
		if (takeProfit.compareTo(entryPrice) <= 0 || stopTrigger.compareTo(entryPrice) >= 0 || stopOrder.signum() <= 0) {
			return new Skipped(SkipReason.INVALID_EXIT_PRICES);
		}

		BigDecimal gapRate = signal.signalClose().signum() == 0
			? BigDecimal.ZERO
			: open.subtract(signal.signalClose()).divide(signal.signalClose(), 6, RoundingMode.HALF_UP);

		return new Entered(new PaperPosition(signal.strategy(), signal.symbol(), signal.rank(), signal.signalDate(), date,
			entryPrice, takeProfit, stopTrigger, stopOrder, signal.atr(), signal.signalClose(),
			new BigDecimal(entryBar.closePrice()), QUANTITY, 0, null, false, clamped, gapRate));
	}

	private static void requireBarDate(Candle bar, LocalDate date) {
		LocalDate barDate = DailyCandles.dateOf(bar);
		if (!barDate.equals(date)) {
			throw new IllegalArgumentException("봉의 날짜(%s)가 처리일(%s)과 다릅니다.".formatted(barDate, date));
		}
	}
}
