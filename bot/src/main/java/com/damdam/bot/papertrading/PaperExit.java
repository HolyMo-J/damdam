package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

// 보유 포지션을 일봉 하나로 청산 판정하는 순수 함수 (docs/strategy.md "실행 방식" 가상 체결 근사, docs/records.md 가상 체결 규칙).
// 일봉만으로는 장중 순서를 알 수 없어서 같은 날 익절가와 손절가를 둘 다 지나면 손절이 먼저다 (결과를 비관적으로 만드는 쪽)
public final class PaperExit {

	// 진입 봉 이후 5번째 봉의 시가에서 시간 청산한다. 실전 HoldingTimeExitService의 MAX_HOLD_TRADING_DAYS(5)와 같은 값이다.
	// papertrading은 liquidation을 import할 수 없어 따로 두었으니, 실전 값을 바꾸면 여기도 함께 바꾼다
	public static final int TIME_EXIT_BAR = 5;

	private PaperExit() {
	}

	public sealed interface Result permits Holding, Exited {
	}

	// 계속 보유. position은 barsProcessed가 1 늘어난 값이다
	public record Holding(PaperPosition position, Note note) implements Result {
	}

	public record Exited(PaperTrade trade) implements Result {
	}

	public enum Note {
		NONE,
		// 거래량 0인 정지일이라 아무것도 체결되지 않음 (하한가와 VI는 일봉으로 판별하기 어려워 감지하지 못한다)
		HALTED,
		// 손절이 발동했지만 그날 고가가 손절 주문가에 못 미쳐 체결 실패. 이후 시간 청산에 고정한다
		SELL_FAILED
	}

	// bar는 date 날짜의 일봉이다. 진입 봉(barsProcessed 0)에서도 익절과 손절을 판정한다
	public static Result evaluate(PaperPosition position, LocalDate date, Candle bar, BigDecimal slippage) {
		Slippage.require(slippage);
		LocalDate barDate = DailyCandles.dateOf(bar);
		if (!barDate.equals(date)) {
			throw new IllegalArgumentException("봉의 날짜(%s)가 처리일(%s)과 다릅니다.".formatted(barDate, date));
		}
		// 같은 봉을 두 번 처리하면 barsProcessed가 이중으로 늘어 시간 청산이 하루 일찍 나고 익절과 손절도 이중 판정된다.
		// 저장한 결과에 같은 날을 다시 넣는 실수를 조용히 넘기지 않고 막는다
		if (position.lastBarDate() != null && !date.isAfter(position.lastBarDate())) {
			throw new IllegalArgumentException("%s 봉은 이미 처리했습니다 (마지막 처리일 %s, 종목 %s)."
				.formatted(date, position.lastBarDate(), position.symbol()));
		}

		BigDecimal open = new BigDecimal(bar.openPrice());
		BigDecimal high = new BigDecimal(bar.highPrice());
		BigDecimal low = new BigDecimal(bar.lowPrice());
		BigDecimal volume = new BigDecimal(bar.volume());

		if (volume.signum() == 0) {
			return new Holding(position.advanced(date, position.sellFailed()), Note.HALTED);
		}

		// 5번째 봉(거래정지로 밀렸으면 거래가 재개된 첫 봉)에서는 익절과 손절을 판정하지 않고 시가에서 시간 청산한다.
		// 시장가라 갭 하락 중에도 체결된다. 슬리피지를 뺀 가격이 그날 저가보다 낮아도 제한하지 않는다 (비관적인 쪽)
		if (position.barsProcessed() >= TIME_EXIT_BAR) {
			BigDecimal price = open.multiply(BigDecimal.ONE.subtract(slippage)).setScale(0, RoundingMode.HALF_UP);
			return exited(position, date, price, PaperTrade.ExitReason.TIME_EXIT, false);
		}

		if (!position.sellFailed()) {
			if (low.compareTo(position.stopTrigger()) <= 0) {
				// 손절 지정가는 주문가에 체결이다. 시가가 주문가 아래로 갭 하락하면 시가에는 체결되지 않고 가격이 주문가까지 다시 올라와야 한다
				if (high.compareTo(position.stopOrderPrice()) >= 0) {
					boolean gap = open.compareTo(position.stopOrderPrice()) < 0;
					return exited(position, date, position.stopOrderPrice(), PaperTrade.ExitReason.STOP_LOSS, gap);
				}
				return new Holding(position.advanced(date, true), Note.SELL_FAILED);
			}
			// 지정가 익절은 고가가 익절가를 엄격히 넘어야 체결이다 (닿기만 해서는 체결로 보지 않음). 갭 상승이어도 익절가에 체결로 계산한다
			if (high.compareTo(position.takeProfitPrice()) > 0) {
				boolean gap = open.compareTo(position.takeProfitPrice()) > 0;
				return exited(position, date, position.takeProfitPrice(), PaperTrade.ExitReason.TAKE_PROFIT, gap);
			}
		}

		return new Holding(position.advanced(date, position.sellFailed()), Note.NONE);
	}

	private static Exited exited(PaperPosition p, LocalDate date, BigDecimal exitPrice,
			PaperTrade.ExitReason reason, boolean gapExit) {
		return new Exited(new PaperTrade(p.strategy(), p.symbol(), p.rank(), p.signalDate(), p.entryDate(), date,
			p.entryPrice(), exitPrice, p.quantity(), reason,
			PaperCosts.netProfit(p.entryPrice(), exitPrice, p.quantity()),
			PaperCosts.netReturn(p.entryPrice(), exitPrice),
			gapExit, p.barsProcessed() == 0, p.entryClampedToHigh(), p.sellFailed(), p.entryGapRate()));
	}
}
