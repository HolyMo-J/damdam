package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

// 저녁 일괄 가상매매의 상태 저장과 CSV 기록을 묶어 거래일 단위로 확정한다 (docs/strategy.md "구현 순서" (d)~(h)).
// 확정 순서는 CSV 기록이 먼저이고 상태 파일 교체가 마지막이다. 그 사이에 죽으면 그 거래일을 처음부터 다시 계산하고,
// trades, skips, bars, signals, abandoned CSV는 같은 키의 같은 행을 무시하므로 두 번 쌓이지 않는다
// (같은 키에 다른 내용이 오면 조용히 넘기지 않고 예외다). runs.csv는 실행 이력이라 재실행하면 줄이 늘어난다
// (분석은 정산일별 마지막 SETTLED 행을 쓴다). 미완료 정산(조회 실패 등으로 봉이 없는 종목이 있는 날)은 결과를 저장하지 않는다.
//
// 호출자(구현 (3))가 지킬 것:
// - 거래일은 봉 날짜로 정해서 빠짐없이 오름차순으로 넘긴다. 휴장일 날짜를 넘기지 않는다 (이 계층은 거래일 캘린더를 모른다)
// - 날짜마다 넘기는 봉 맵에 기준 종목(예: 대형주 하나)의 봉을 함께 넣는다. 맵이 통째로 비어 있으면 종목이 아니라 조회 전체가
//   실패한 것(403, 장애, 휴장일)으로 보고 포기 판정을 하지 않는다. 포기는 "다른 종목의 봉은 왔는데 이 종목만 없다"일 때만 한다
// - 이 원장은 PaperRunLock을 받아야 만들 수 있고, 잠금이 풀린 뒤에는 쓸 수 없다 (bars.csv와 runs.csv를 전략이 공유한다)
public final class PaperLedger {

	// 봉이 없어 미확정으로 끝난 달력 날짜가 이만큼 연속되면 그 종목을 포기한다 (2026-09-30 사용자 승인).
	// 같은 날 재시도는 세지 않는다. 일시 장애는 넘기고 상장폐지 같은 영구 실패가 정산을 오래 막지는 않게 한 값이다
	public static final int MAX_UNSETTLED_ATTEMPTS = 3;

	private final Path stateDirectory;
	private final PaperStateStore store;
	private final Path recordsDirectory;
	private final BigDecimal slippage;
	private final String strategyVersion;
	private final Clock clock;
	private final PaperRunLock lock;
	private final CsvTable bars;
	private final CsvTable runs;
	private final ObjectMapper objectMapper = new ObjectMapper();

	// stateDirectory: 상태 JSON을 두는 곳(bot/data/paper), recordsDirectory: CSV를 두는 곳(records/paper, git 제외).
	// 슬리피지나 전략 버전을 바꿔 돌리려면(민감도 실험) 다른 폴더를 써야 한다 (같은 폴더는 설정 파일이 거부한다)
	public PaperLedger(Path stateDirectory, Path recordsDirectory, String strategyVersion, BigDecimal slippage, Clock clock,
			PaperRunLock lock) {
		Slippage.require(slippage);
		requireKoreanTime(clock);
		this.stateDirectory = stateDirectory;
		this.store = new PaperStateStore(stateDirectory);
		this.recordsDirectory = recordsDirectory;
		this.slippage = slippage;
		this.strategyVersion = Objects.requireNonNull(strategyVersion);
		this.clock = clock;
		this.lock = Objects.requireNonNull(lock);
		this.bars = new CsvTable(recordsDirectory.resolve("bars.csv"), PaperRecords.BARS_HEADER, PaperRecords.BARS_KEY);
		this.runs = new CsvTable(recordsDirectory.resolve("runs.csv"), PaperRecords.RUNS_HEADER, PaperRecords.RUNS_KEY);
	}

	// 달력 날짜는 이 시계로 센다(같은 날 재시도 판정). UTC 시계면 서울 자정부터 아침 9시 사이에 전날 날짜가 나와 경계가 밀린다.
	// 서버 이전 뒤 기본 시간대가 UTC인 서버에서 Clock.systemDefaultZone()을 쓰는 실수도 여기서 시작할 때 막힌다.
	// ZoneId 동등 비교는 +09:00 오프셋 시계를 거부하므로, 그 시각의 오프셋이 UTC+9인지 본다 (한국은 서머타임이 없다)
	private static void requireKoreanTime(Clock clock) {
		ZoneOffset offset = clock.getZone().getRules().getOffset(clock.instant());
		if (!offset.equals(ZoneOffset.ofHours(9))) {
			throw new IllegalArgumentException("Clock은 한국 시간대(UTC+9)여야 합니다: " + clock.getZone());
		}
	}

	// settledDates: 이번 호출에서 확정한 거래일(오름차순). unsettledSymbols가 비어 있지 않으면 마지막으로 시도한 거래일이 미확정이다.
	// systemicMiss: 그 거래일의 봉 맵이 통째로 비어 있어서 종목 문제가 아니라 조회 전체의 결측으로 보고 멈춘 경우
	public record Report(PaperStrategyState state, List<LocalDate> settledDates, List<String> unsettledSymbols,
			List<String> abandonedSymbols, boolean systemicMiss) {

		public boolean complete() {
			return unsettledSymbols.isEmpty();
		}
	}

	// 슬리피지와 전략 버전을 이 폴더의 첫 사용 때 기록하고, 이후 다른 값으로 열면 거부한다.
	// 진입은 옛 슬리피지로 했는데 청산은 새 슬리피지로 계산하는 혼합이 기록에 조용히 들어가는 것을 막는다
	public record Config(String slippage, String strategyVersion) {
	}

	public PaperStrategyState loadState(String strategy) {
		requireReady();
		return store.load(strategy);
	}

	// 마지막 확정일 이후의 거래일을 날짜 순서대로 정산한다. barsByDate는 거래일별 종목별 일봉이고, 확정일보다 뒤 날짜만 담아야 한다
	// (이미 확정한 날짜가 들어 있으면 이중 처리를 막으려고 예외를 던진다). 미확정 날이 나오면 거기서 멈춘다
	public Report settleDays(String strategy, SortedMap<LocalDate, Map<String, Candle>> barsByDate) {
		requireReady();
		PaperStrategyState state = store.load(strategy);
		LocalDate today = LocalDate.now(clock);
		List<LocalDate> settledDates = new ArrayList<>();
		List<String> abandonedNow = new ArrayList<>();

		for (Map.Entry<LocalDate, Map<String, Candle>> entry : barsByDate.entrySet()) {
			LocalDate date = entry.getKey();
			if (state.settledThroughDate() != null && !date.isAfter(state.settledThroughDate())) {
				throw new IllegalArgumentException("%s은(는) 이미 확정한 거래일입니다 (확정일 %s).".formatted(date, state.settledThroughDate()));
			}
			List<PaperDaySettlement.SkippedSignal> abandonedSkips = new ArrayList<>();

			PaperDaySettlement.Result result;
			while (true) {
				result = PaperDaySettlement.settle(strategy, date, state.positions(), state.closedTrades(), state.pending(),
					entry.getValue(), slippage);
				if (result.complete()) {
					break;
				}
				// 봉 맵이 통째로 비었으면 종목이 아니라 조회 전체의 결측이다. 횟수를 올리지 않고 포기하지 않은 채 멈춘다
				boolean systemic = entry.getValue().isEmpty();
				if (systemic) {
					persistWithoutSettlement(state, date, abandonedSkips, result.unsettledSymbols(), abandonedNow,
						"no_bars_at_all_not_counted");
					return new Report(state, settledDates, result.unsettledSymbols(), abandonedNow, true);
				}
				Map<String, Integer> attempts = nextAttempts(state, result.unsettledSymbols(), today);
				List<String> giveUp = result.unsettledSymbols().stream()
					.filter(symbol -> attempts.get(symbol) >= MAX_UNSETTLED_ATTEMPTS).toList();
				if (giveUp.isEmpty()) {
					// 정산 결과는 저장하지 않고 시도 횟수와 앞서 포기한 것만 남긴다
					state = state.withAttempts(attempts, today);
					persistWithoutSettlement(state, date, abandonedSkips, result.unsettledSymbols(), abandonedNow,
						"missing_bars_day_not_settled");
					return new Report(state, settledDates, result.unsettledSymbols(), abandonedNow, false);
				}
				for (PendingSignal signal : state.pending()) {
					if (giveUp.contains(signal.symbol())) {
						abandonedSkips.add(new PaperDaySettlement.SkippedSignal(signal, SkipReason.NO_BAR));
					}
				}
				// 포기한 포지션은 정산 대상에서 빠지므로 CSV에 남긴다 (통과 판정 보고에 "청산되지 않은 포지션"으로 병기한다)
				List<PaperPosition> givenUpPositions = state.positions().stream().filter(p -> giveUp.contains(p.symbol())).toList();
				writeAbandoned(strategy, givenUpPositions, date);
				Map<String, Integer> remaining = new TreeMap<>(attempts);
				giveUp.forEach(remaining::remove);
				// 오늘 이미 센 것으로 표시해서, 같은 실행에서 같은 날을 다시 계산할 때 횟수가 이중으로 늘지 않게 한다
				state = state.withAbandoned(giveUp, date).withAttempts(remaining, today);
				abandonedNow.addAll(giveUp);
			}

			commit(state, date, entry.getValue(), result, abandonedSkips, abandonedNow);
			state = state.settled(date, result.positions(), result.newTrades());
			store.save(state);
			settledDates.add(date);
		}
		return new Report(state, settledDates, List.of(), abandonedNow, false);
	}

	// 신호일 저녁에 만든 다음 거래일 진입 대기 신호를 저장한다. 신호일이 이미 확정된 뒤여야 하고,
	// 같은 신호일로 다시 부르면 대기 신호를 교체한다 (재실행 안전). 다른 신호일의 처리 안 된 대기 신호가 남아 있으면 예외다.
	// 신호(순위, 신호일 ATR, 신호일 종가)는 signals CSV에도 남긴다. 청산되면 상태에서 사라지는 값이라
	// 슬리피지만 바꿔 같은 코드로 다시 돌릴 때의 입력이다
	public PaperStrategyState recordPending(String strategy, LocalDate signalDate, List<PendingSignal> signals) {
		requireReady();
		PaperStrategyState state = store.load(strategy);
		if (!signalDate.equals(state.settledThroughDate())) {
			throw new IllegalStateException("신호일(%s)이 확정일(%s)과 같아야 대기 신호를 저장할 수 있습니다."
				.formatted(signalDate, state.settledThroughDate()));
		}
		Set<String> symbols = new HashSet<>();
		for (PendingSignal signal : signals) {
			if (!signal.strategy().equals(strategy) || !signal.signalDate().equals(signalDate)) {
				throw new IllegalArgumentException("전략 또는 신호일이 다른 대기 신호입니다: " + signal);
			}
			if (!symbols.add(signal.symbol())) {
				throw new IllegalArgumentException("같은 종목의 대기 신호가 둘 이상입니다: " + signal.symbol());
			}
		}
		boolean leftover = state.pending().stream().anyMatch(p -> !p.signalDate().equals(signalDate));
		if (leftover) {
			throw new IllegalStateException("이전 신호일의 처리 안 된 대기 신호가 남아 있습니다.");
		}
		csv("signals_" + strategy + ".csv", PaperRecords.SIGNALS_HEADER, PaperRecords.SIGNALS_KEY)
			.append(signals.stream().map(PaperRecords::signalRow).toList());
		PaperStrategyState next = state.withPending(signals);
		store.save(next);
		return next;
	}

	// 신호를 만들 수 없었던 날(확정 전이라 게이트에 걸림, PC가 꺼졌던 공백 등)을 실행 상태 기록에 남긴다
	public void recordRun(String strategy, String status, LocalDate date, String note) {
		requireReady();
		PaperStrategyState state = store.load(strategy);
		runs.append(List.of(PaperRecords.runRow(clock.instant(), strategy, status, date, 0, 0, state.positions().size(),
			List.of(), List.of(), note)));
	}

	private void commit(PaperStrategyState before, LocalDate date, Map<String, Candle> barsOfDay,
			PaperDaySettlement.Result result, List<PaperDaySettlement.SkippedSignal> abandonedSkips, List<String> abandonedNow) {
		String strategy = before.strategy();
		csv("trades_" + strategy + ".csv", PaperRecords.TRADES_HEADER, PaperRecords.TRADES_KEY)
			.append(result.newTrades().stream().map(t -> PaperRecords.tradeRow(t, slippage, strategyVersion)).toList());

		List<PaperDaySettlement.SkippedSignal> allSkipped = new ArrayList<>(result.skipped());
		allSkipped.addAll(abandonedSkips);
		csv("skips_" + strategy + ".csv", PaperRecords.SKIPS_HEADER, PaperRecords.SKIPS_KEY)
			.append(allSkipped.stream().map(s -> PaperRecords.skipRow(s, date, slippage, strategyVersion)).toList());

		// 이 전략이 그날 다룬 종목(보유 중이거나 대기 신호가 있는 종목)의 봉만 남긴다
		Set<String> relevant = new HashSet<>();
		before.positions().forEach(p -> relevant.add(p.symbol()));
		before.pending().forEach(s -> relevant.add(s.symbol()));
		bars.append(barsOfDay.entrySet().stream().filter(e -> relevant.contains(e.getKey()))
			.map(e -> PaperRecords.barRow(e.getKey(), e.getValue())).toList());

		runs.append(List.of(PaperRecords.runRow(clock.instant(), strategy, "SETTLED", date, result.newTrades().size(),
			allSkipped.size(), result.positions().size(), List.of(), abandonedNow, "")));
	}

	// 미확정으로 멈추는 경우. 정산 결과가 아니라 시도 횟수와 포기 결정만 저장한다.
	// CSV(포기한 신호의 NO_BAR 기록과 실행 상태)를 먼저 쓰고 상태 파일을 마지막에 교체한다
	private void persistWithoutSettlement(PaperStrategyState state, LocalDate date,
			List<PaperDaySettlement.SkippedSignal> abandonedSkips, List<String> unsettled, List<String> abandonedNow,
			String note) {
		String strategy = state.strategy();
		csv("skips_" + strategy + ".csv", PaperRecords.SKIPS_HEADER, PaperRecords.SKIPS_KEY)
			.append(abandonedSkips.stream().map(s -> PaperRecords.skipRow(s, date, slippage, strategyVersion)).toList());
		runs.append(List.of(PaperRecords.runRow(clock.instant(), strategy, "INCOMPLETE", date, 0, abandonedSkips.size(),
			state.positions().size(), unsettled, abandonedNow, note)));
		store.save(state);
	}

	private void writeAbandoned(String strategy, List<PaperPosition> positions, LocalDate abandonedOn) {
		csv("abandoned_" + strategy + ".csv", PaperRecords.ABANDONED_HEADER, PaperRecords.ABANDONED_KEY)
			.append(positions.stream().map(p -> PaperRecords.abandonedRow(p, abandonedOn, slippage, strategyVersion)).toList());
	}

	// 종목별 연속 미확정 날짜 수를 하나 늘린다. 이번에 봉이 없는 종목만 남고 나머지(봉이 온 종목)는 사라진다.
	// 오늘(달력 날짜) 이미 센 상태면 늘리지 않는다: 작업 스케줄러의 재시도나 수동 재실행이 하루에 여러 번 있어도 하루로 센다
	private static Map<String, Integer> nextAttempts(PaperStrategyState state, List<String> unsettled, LocalDate today) {
		boolean alreadyCountedToday = today.equals(state.lastAttemptDate());
		return unsettled.stream().collect(Collectors.toMap(symbol -> symbol, symbol -> {
			Integer previous = state.unsettledAttempts().get(symbol);
			if (previous == null) {
				return 1;
			}
			return alreadyCountedToday ? previous : previous + 1;
		}, (a, b) -> a, TreeMap::new));
	}

	private CsvTable csv(String fileName, List<String> header, int keyColumns) {
		return new CsvTable(recordsDirectory.resolve(fileName), header, keyColumns);
	}

	// 잠금을 쥐고 있는지, 이 폴더가 같은 슬리피지와 전략 버전으로 쓰이던 곳인지 확인한다
	private void requireReady() {
		if (!lock.isHeld()) {
			throw new IllegalStateException("PaperRunLock을 쥐고 있어야 원장을 쓸 수 있습니다.");
		}
		Path file = stateDirectory.resolve("ledger_config.json");
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(stateDirectory);
				objectMapper.writeValue(file.toFile(), new Config(slippage.toPlainString(), strategyVersion));
				return;
			}
			Config existing = objectMapper.readValue(file.toFile(), Config.class);
			if (new BigDecimal(existing.slippage()).compareTo(slippage) != 0 || !existing.strategyVersion().equals(strategyVersion)) {
				throw new IllegalStateException("이 폴더는 슬리피지 %s, 전략 버전 %s로 쓰던 곳입니다 (지금 설정: %s, %s). 다른 설정으로 돌리려면 다른 폴더를 쓰세요."
					.formatted(existing.slippage(), existing.strategyVersion(), slippage.toPlainString(), strategyVersion));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("원장 설정 파일을 다루지 못했습니다: " + file, e);
		} catch (JacksonException | NumberFormatException e) {
			throw new IllegalStateException("원장 설정 파일을 읽지 못했습니다. 확인하세요: " + file);
		}
	}
}
