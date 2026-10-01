package com.damdam.bot.papertrading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

// 한 전략의 저장 상태 (불변). 순수 로직(PaperDaySettlement)이 다음 거래일에 이어받는 입력 전부다.
// 저장은 PaperStateStore가 JSON으로 하고, 이 값이 바뀌는 시점이 곧 "확정"이다 (PaperLedger)
public record PaperStrategyState(
	String strategy,
	// 이 날짜까지 정산을 확정했다. 처음이면 null
	LocalDate settledThroughDate,
	List<PaperPosition> positions,
	// 신호일 저녁에 만든 다음 거래일 진입 대기 신호. 정산이 확정되면 모두 처리되어 비워진다
	List<PendingSignal> pending,
	// 청산 완료 거래 전부. 주간 손실 한도 판정에 쓰므로 CSV를 다시 읽지 않고 여기 둔다
	List<PaperTrade> closedTrades,
	// 봉이 끝내 오지 않아 포기한 포지션. 정산 대상에서 빠지고 판정 보고에 "청산되지 않은 포지션"으로 병기한다.
	// 청산 여부를 알 수 없으므로 총 노출에는 영구히 넣는다 (빼는 코드가 없다, PaperDaySettlement)
	List<Abandoned> abandoned,
	// 봉을 못 받아 미확정으로 끝난 날짜 수(종목별, 연속). 정산이 확정되면 비운다
	Map<String, Integer> unsettledAttempts,
	// 위 횟수를 마지막으로 센 달력 날짜(Asia/Seoul). 같은 날 재시도는 횟수를 올리지 않는 근거다
	LocalDate lastAttemptDate
) {

	public record Abandoned(PaperPosition position, LocalDate abandonedOn) {
	}

	// JSON에 없는 필드는 null로 들어오므로 빈 값으로 채운다
	public PaperStrategyState {
		Objects.requireNonNull(strategy);
		positions = positions == null ? List.of() : List.copyOf(positions);
		pending = pending == null ? List.of() : List.copyOf(pending);
		closedTrades = closedTrades == null ? List.of() : List.copyOf(closedTrades);
		abandoned = abandoned == null ? List.of() : List.copyOf(abandoned);
		unsettledAttempts = unsettledAttempts == null
			? Map.of() : Collections.unmodifiableMap(new TreeMap<>(unsettledAttempts));
	}

	public static PaperStrategyState initial(String strategy) {
		return new PaperStrategyState(strategy, null, List.of(), List.of(), List.of(), List.of(), Map.of(), null);
	}

	PaperStrategyState withPending(List<PendingSignal> newPending) {
		return new PaperStrategyState(strategy, settledThroughDate, positions, newPending, closedTrades, abandoned,
			unsettledAttempts, lastAttemptDate);
	}

	PaperStrategyState withAttempts(Map<String, Integer> attempts, LocalDate countedOn) {
		return new PaperStrategyState(strategy, settledThroughDate, positions, pending, closedTrades, abandoned, attempts,
			countedOn);
	}

	// symbols의 보유 포지션을 포기 목록으로 옮기고, 그 종목의 대기 신호를 뺀다
	PaperStrategyState withAbandoned(List<String> symbols, LocalDate date) {
		List<PaperPosition> kept = new ArrayList<>();
		List<Abandoned> nowAbandoned = new ArrayList<>(abandoned);
		for (PaperPosition p : positions) {
			if (symbols.contains(p.symbol())) {
				nowAbandoned.add(new Abandoned(p, date));
			} else {
				kept.add(p);
			}
		}
		List<PendingSignal> keptPending = pending.stream().filter(s -> !symbols.contains(s.symbol())).toList();
		return new PaperStrategyState(strategy, settledThroughDate, kept, keptPending, closedTrades, nowAbandoned,
			unsettledAttempts, lastAttemptDate);
	}

	// 거래일 하나를 확정한 결과. 대기 신호는 모두 처리됐으므로 비우고, 미확정 횟수도 비운다
	PaperStrategyState settled(LocalDate date, List<PaperPosition> newPositions, List<PaperTrade> newTrades) {
		List<PaperTrade> trades = new ArrayList<>(closedTrades);
		trades.addAll(newTrades);
		return new PaperStrategyState(strategy, date, newPositions, List.of(), trades, abandoned, Map.of(), null);
	}
}
