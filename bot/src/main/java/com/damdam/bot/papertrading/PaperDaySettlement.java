package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

// 한 전략의 거래일 하나를 정산하는 순수 함수 (docs/strategy.md "실행 방식" 같은 저녁의 처리 순서).
// 순서: (1) 대기 신호를 순위 순으로 진입 처리 (2) 보유 종목(오늘 진입한 것 포함)을 오늘 봉으로 청산 판정.
// 진입에는 오늘 이전에 청산된 포지션이 비운 노출만 반영한다. 실제로는 시가에 사고 청산은 장중에 일어나기 때문이다.
// 총 노출 한도나 주간 정지로 건너뛴 신호는 다음 날로 이월하지 않고 사유와 함께 돌려준다
public final class PaperDaySettlement {

	private PaperDaySettlement() {
	}

	public record SkippedSignal(PendingSignal signal, SkipReason reason) {
	}

	public record HoldNote(String symbol, PaperExit.Note note) {
	}

	// unsettledSymbols: 종목코드 오름차순. 오늘 봉이 없어 처리하지 못한 종목(조회 실패 등). 비어 있지 않으면 호출자는 이 거래일을 확정하지 않아야 한다.
	// 그 종목의 보유 포지션은 그대로 돌려주고, 대기 신호는 진입도 건너뜀 기록도 하지 않는다 (다시 정산할 때 처리한다)
	public record Result(List<PaperPosition> positions, List<PaperTrade> newTrades, List<SkippedSignal> skipped,
			List<HoldNote> notes, List<String> unsettledSymbols) {

		public boolean complete() {
			return unsettledSymbols.isEmpty();
		}
	}

	// positions: 오늘 이전에 청산되지 않은 보유 포지션, abandonedPositions: 봉이 끝내 안 와 포기한 포지션(정산하지 않고 돌려주지도 않지만
	// 청산 여부를 알 수 없으므로 총 노출에는 계속 넣는다, 2026-09-30 사용자 결정. 주간 손실 판정에는 넣지 않는다),
	// closedTrades: 이 전략의 청산 완료 거래(주간 손실 판정용), pending: 어제까지 만든 대기 신호, barsBySymbol: 오늘 날짜의 일봉
	public static Result settle(String strategy, LocalDate date, List<PaperPosition> positions,
			List<PaperPosition> abandonedPositions, List<PaperTrade> closedTrades, List<PendingSignal> pending,
			Map<String, Candle> barsBySymbol, BigDecimal slippage) {
		Slippage.require(slippage);
		validate(strategy, date, positions, abandonedPositions, closedTrades, pending, barsBySymbol);

		List<SkippedSignal> skipped = new ArrayList<>();
		Set<String> unsettled = new TreeSet<>();
		Set<String> heldSymbols = new HashSet<>();
		positions.forEach(p -> heldSymbols.add(p.symbol()));
		// 포기한 종목도 청산되지 않은 채 남은 포지션이다. 거래정지가 풀리거나 조회가 복구돼 다시 신호가 나도 둘째 포지션을 만들지 않는다
		// (만들면 보유와 포기에 같은 종목이 생겨 validate()가 이후 모든 정산을 예외로 멈춘다)
		abandonedPositions.forEach(p -> heldSymbols.add(p.symbol()));

		boolean weeklyHalt = WeeklyLossLimit.isHalted(date, closedTrades);
		BigDecimal exposure = ExposureLimit.exposureOf(positions).add(ExposureLimit.exposureOf(abandonedPositions));

		List<PaperPosition> entered = new ArrayList<>();
		List<PendingSignal> ordered = pending.stream().sorted(Comparator.comparingInt(PendingSignal::rank)).toList();
		for (PendingSignal signal : ordered) {
			if (heldSymbols.contains(signal.symbol())) {
				skipped.add(new SkippedSignal(signal, SkipReason.ALREADY_HELD));
				continue;
			}
			if (weeklyHalt) {
				skipped.add(new SkippedSignal(signal, SkipReason.WEEKLY_HALT));
				continue;
			}
			Candle bar = barsBySymbol.get(signal.symbol());
			if (bar == null) {
				unsettled.add(signal.symbol());
				continue;
			}
			PaperEntry.Result entry = PaperEntry.enter(signal, date, bar, slippage);
			if (entry instanceof PaperEntry.Skipped s) {
				skipped.add(new SkippedSignal(signal, s.reason()));
				continue;
			}
			PaperPosition position = ((PaperEntry.Entered) entry).position();
			Optional<SkipReason> blocker = ExposureLimit.blocker(position.exposure(), exposure);
			if (blocker.isPresent()) {
				skipped.add(new SkippedSignal(signal, blocker.get()));
				continue;
			}
			exposure = exposure.add(position.exposure());
			heldSymbols.add(signal.symbol());
			entered.add(position);
		}

		List<PaperPosition> remaining = new ArrayList<>();
		List<PaperTrade> newTrades = new ArrayList<>();
		List<HoldNote> notes = new ArrayList<>();
		List<PaperPosition> toEvaluate = new ArrayList<>(positions);
		toEvaluate.addAll(entered);
		for (PaperPosition position : toEvaluate) {
			Candle bar = barsBySymbol.get(position.symbol());
			if (bar == null) {
				unsettled.add(position.symbol());
				remaining.add(position);
				continue;
			}
			switch (PaperExit.evaluate(position, date, bar, slippage)) {
				case PaperExit.Holding h -> {
					remaining.add(h.position());
					if (h.note() != PaperExit.Note.NONE) {
						notes.add(new HoldNote(position.symbol(), h.note()));
					}
				}
				case PaperExit.Exited e -> newTrades.add(e.trade());
			}
		}

		return new Result(List.copyOf(remaining), List.copyOf(newTrades), List.copyOf(skipped), List.copyOf(notes),
			List.copyOf(unsettled));
	}

	private static void validate(String strategy, LocalDate date, List<PaperPosition> positions,
			List<PaperPosition> abandonedPositions, List<PaperTrade> closedTrades, List<PendingSignal> pending,
			Map<String, Candle> barsBySymbol) {
		Set<String> symbols = new HashSet<>();
		for (PaperPosition p : positions) {
			if (!p.strategy().equals(strategy)) {
				throw new IllegalArgumentException("다른 전략의 포지션이 섞였습니다: " + p.strategy() + " (기대: " + strategy + ")");
			}
			if (!symbols.add(p.symbol())) {
				throw new IllegalArgumentException("같은 종목의 포지션이 둘 이상입니다: " + p.symbol());
			}
		}
		// 포기한 포지션은 보유 목록에서 옮겨진 것이라 보유 목록과 겹치면 노출이 이중으로 잡히는 상태 오류다
		for (PaperPosition p : abandonedPositions) {
			if (!p.strategy().equals(strategy)) {
				throw new IllegalArgumentException("다른 전략의 포기 포지션이 섞였습니다: " + p.strategy() + " (기대: " + strategy + ")");
			}
			if (symbols.contains(p.symbol())) {
				throw new IllegalArgumentException("보유 중인 종목이 포기 목록에도 있습니다: " + p.symbol());
			}
		}
		// 두 전략의 거래를 합쳐 넘기면 주간 손실 정지가 섞인다
		for (PaperTrade t : closedTrades) {
			if (!t.strategy().equals(strategy)) {
				throw new IllegalArgumentException("다른 전략의 청산 거래가 섞였습니다: " + t.strategy() + " (기대: " + strategy + ")");
			}
		}
		for (PendingSignal s : pending) {
			if (!s.strategy().equals(strategy)) {
				throw new IllegalArgumentException("다른 전략의 대기 신호가 섞였습니다: " + s.strategy() + " (기대: " + strategy + ")");
			}
		}
		// 봉의 날짜가 정산일과 다르면 어제 봉으로 오늘을 정산하는 조용한 오류가 된다
		for (Map.Entry<String, Candle> entry : barsBySymbol.entrySet()) {
			LocalDate barDate = DailyCandles.dateOf(entry.getValue());
			if (!barDate.equals(date)) {
				throw new IllegalArgumentException("%s 봉의 날짜(%s)가 정산일(%s)과 다릅니다.".formatted(entry.getKey(), barDate, date));
			}
		}
	}
}
