package com.damdam.bot.papertrader;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.papertrading.PaperLedger;
import com.damdam.bot.papertrading.PaperPosition;
import com.damdam.bot.papertrading.PaperRunLock;
import com.damdam.bot.papertrading.PaperStrategyState;
import com.damdam.bot.papertrading.PendingSignal;
import com.damdam.bot.papertrading.SignalDates;
import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.SignalScanService;
import com.damdam.bot.papertrading.TargetUniverse;
import com.damdam.bot.papertrading.TargetUniverseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

// paper-run 프로필로 실행할 때만 동작하는 저녁 일괄 가상매매 러너 (docs/strategy.md "실행 방식" 구현 순서 (3)). 한 번 돌고 끝난다.
// 작업 스케줄러가 저녁에 부르는 것을 전제로 한다. 실제 주문 경로를 쓰지 않는다: 이 패키지는 papertrading, market, ranking, stocks,
// notification만 쓰고 PaperTradingIsolationTest가 그 경계를 지킨다 (1단계 청산 봇의 스케줄러와 웹소켓은 listen 프로필에만 있다).
//
// 한 번 실행의 순서:
//   1. 신호일을 정한다 (오늘이 영업일이고 20:30 이후면 오늘, 아니면 직전 영업일)
//   2. 잠금을 쥐고 원장을 연다. 이미 다른 실행이 쥐고 있으면 실패다. 원장 설정 파일이 없는 폴더는 --init-ledger 없이는 열지 않는다
//   3. 전략 A, B를 신호일까지 정산한다 (보유 종목 청산 판정과 어제 만든 대기 신호의 진입). PC가 꺼졌던 날은 밀린 거래일을 한꺼번에
//      정산하고, 그 날들은 신호를 만들 수 없으므로 SIGNAL_GAP으로 남긴다
//   4. 같은 신호일의 대기 신호가 이미 저장된 전략은 다시 판정하지 않는다 (재실행이 정상 실행의 신호를 덮어쓰지 않게)
//   5. 나머지 전략이 있으면 대상 종목군을 만들고 신호 시각을 검사한 뒤 신호를 판정해 scan_rows.csv에 남긴다
//   6. 전략마다 대기 신호를 저장하고, 못 만든 날은 SIGNAL_GAP으로 남긴다 (신호 없음과 구분하기 위함)
//   7. 결과를 디스코드로 알린다. 실패하면 알린 뒤 예외를 다시 던져서 비정상 종료한다 (작업 스케줄러가 실패로 기록한다).
//      공백이나 미확정 정산은 종료 코드를 바꾸지 않고 [주의] 알림으로만 알린다
// 수정주가 보정과 포기한 포지션의 별도 알림은 아직 없다 (구현 (3-b)).
@Component
@Profile("paper-run")
class PaperRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(PaperRunner.class);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final String SIGNAL_GAP = "SIGNAL_GAP";
	// 신호일의 대기 신호를 저장했다는 표시 (0건 저장도 포함). 재실행이 이 날을 다시 판정하지 않게 하는 근거다
	static final String SIGNALS_SAVED = "SIGNALS_SAVED";
	static final String INIT_LEDGER_ARG = "--init-ledger";
	private static final String LEDGER_CONFIG_FILE = "ledger_config.json";

	// stateDirectory: 상태 JSON(bot/data 아래, git 제외), recordsDirectory: CSV(records 아래, git 제외).
	// 같은 폴더를 다른 슬리피지나 전략 버전으로 열면 원장이 거부한다 (PaperLedger)
	record Settings(Path stateDirectory, Path recordsDirectory, Path lockFile, BigDecimal slippage, String strategyVersion,
			String referenceSymbol) {
	}

	private final TargetUniverseService universeService;
	private final SignalScanService scanService;
	private final SettlementBarsLoader barsLoader;
	private final MarketCalendarService calendarService;
	private final Notifier notifier;
	private final Settings settings;
	private final Clock clock;

	@Autowired
	PaperRunner(TargetUniverseService universeService, SignalScanService scanService, SettlementBarsLoader barsLoader,
			MarketCalendarService calendarService, Notifier notifier,
			@Value("${damdam.paper.state-dir}") String stateDirectory,
			@Value("${damdam.paper.records-dir}") String recordsDirectory,
			@Value("${damdam.paper.lock-file}") String lockFile,
			@Value("${damdam.paper.slippage}") String slippage,
			@Value("${damdam.paper.strategy-version}") String strategyVersion,
			@Value("${damdam.paper.reference-symbol}") String referenceSymbol) {
		this(universeService, scanService, barsLoader, calendarService, notifier,
			new Settings(absolute(stateDirectory), absolute(recordsDirectory), absolute(lockFile), new BigDecimal(slippage),
				strategyVersion, referenceSymbol),
			Clock.system(KST));
	}

	// 테스트에서 실제 저장소 폴더를 건드리지 않도록 설정과 시계를 바꿔 끼울 수 있게 둔다
	PaperRunner(TargetUniverseService universeService, SignalScanService scanService, SettlementBarsLoader barsLoader,
			MarketCalendarService calendarService, Notifier notifier, Settings settings, Clock clock) {
		this.universeService = universeService;
		this.scanService = scanService;
		this.barsLoader = barsLoader;
		this.calendarService = calendarService;
		this.notifier = notifier;
		this.settings = settings;
		this.clock = clock;
	}

	// 상대 경로는 실행한 작업 폴더 기준이라 작업 스케줄러가 다른 폴더에서 띄우면 엉뚱한 곳을 가리킨다. 시작 때 절대 경로로 고정하고 로그에 남긴다
	private static Path absolute(String path) {
		return Path.of(path).toAbsolutePath().normalize();
	}

	@Override
	public void run(String... args) {
		ZonedDateTime now = ZonedDateTime.now(clock);
		boolean initLedger = Arrays.asList(args).contains(INIT_LEDGER_ARG);
		try {
			LocalDate signalDate = SignalDates.defaultSignalDate(now, calendarService::isTradingDay);
			log.info("[가상매매] 실행 시작 {} (KST), 신호일 {}, 상태 폴더 {}, 기록 폴더 {}", now.toLocalDateTime().withNano(0), signalDate,
				settings.stateDirectory(), settings.recordsDirectory());
			try (PaperRunLock lock = PaperRunLock.tryAcquire(settings.lockFile())
				.orElseThrow(() -> new IllegalStateException("다른 가상매매 실행이 잠금을 쥐고 있습니다: " + settings.lockFile()))) {
				execute(signalDate, now, lock, initLedger);
			}
		} catch (RuntimeException e) {
			log.error("[가상매매] 실행 실패: {}", e.getMessage(), e);
			notifier.sendNow("[가상매매 실패] " + e.getClass().getSimpleName() + ": " + e.getMessage());
			throw e;
		}
	}

	private void execute(LocalDate signalDate, ZonedDateTime now, PaperRunLock lock, boolean initLedger) {
		requireExistingLedgerUnlessInitializing(initLedger);
		PaperLedger ledger = new PaperLedger(settings.stateDirectory(), settings.recordsDirectory(), settings.strategyVersion(),
			settings.slippage(), clock, lock);

		Map<String, PaperStrategyState> states = new LinkedHashMap<>();
		for (String strategy : Strategies.ALL) {
			PaperStrategyState state = ledger.loadState(strategy);
			if (state.settledThroughDate() != null && state.settledThroughDate().isAfter(signalDate)) {
				throw new IllegalStateException("전략 %s의 확정일(%s)이 신호일(%s)보다 뒤입니다.".formatted(strategy, state.settledThroughDate(), signalDate));
			}
			states.putIfAbsent(strategy, state);
		}

		// 정산에 봉이 필요한 종목: 보유 중이거나 대기 신호가 있는 종목과, 조회 전체 결측을 가려내는 기준 종목
		Set<String> symbols = new LinkedHashSet<>();
		symbols.add(settings.referenceSymbol());
		for (PaperStrategyState state : states.values()) {
			state.positions().stream().map(PaperPosition::symbol).forEach(symbols::add);
			state.pending().stream().map(PendingSignal::symbol).forEach(symbols::add);
		}
		LocalDate earliestFrom = states.values().stream().map(s -> settleFrom(s, signalDate)).min(LocalDate::compareTo).orElse(signalDate);
		// 봉 조회가 여기서 실패하면 아직 아무것도 확정하지 않았으므로 기록할 것이 없다 (다음 실행이 같은 날부터 다시 정산한다)
		SettlementBarsLoader.Loaded loaded = barsLoader.load(earliestFrom, signalDate, symbols);

		// 이 지점부터는 전략 하나의 정산이 이미 확정됐을 수 있다. 끝까지 가지 못하고 예외가 나면 대기 신호나 공백 기록을 아직 못 남긴
		// 전략에 공백을 best-effort로 남기고 예외를 다시 던진다 (정산만 있고 신호 기록이 없는 날이 "신호 없음"으로 보이지 않게)
		Set<String> recorded = new HashSet<>();
		try {
			executeAfterLoading(ledger, states, loaded, signalDate, now, recorded);
		} catch (RuntimeException e) {
			recordGapsBestEffort(ledger, signalDate, recorded, e);
			throw e;
		}
	}

	private void executeAfterLoading(PaperLedger ledger, Map<String, PaperStrategyState> states, SettlementBarsLoader.Loaded loaded,
			LocalDate signalDate, ZonedDateTime now, Set<String> recorded) {
		Map<String, PaperLedger.Report> reports = new LinkedHashMap<>();
		List<String> ready = new ArrayList<>();
		Map<String, String> gaps = new LinkedHashMap<>();
		for (String strategy : Strategies.ALL) {
			SortedMap<LocalDate, Map<String, Candle>> bars =
				new TreeMap<>(loaded.barsByDate().tailMap(settleFrom(states.get(strategy), signalDate)));
			PaperLedger.Report report = ledger.settleDays(strategy, bars);
			reports.putIfAbsent(strategy, report);
			// PC가 꺼졌던 날은 포지션을 봉으로 소급 정산하지만 그날의 순위는 되돌릴 수 없어 신호를 만들 수 없다 (docs/strategy.md "한계").
			// 그 날들을 공백으로 남겨 "그날 신호 0건"과 구분한다
			for (LocalDate settled : report.settledDates()) {
				if (!settled.equals(signalDate)) {
					ledger.recordRun(strategy, SIGNAL_GAP, settled, "소급 불가: 이 날 실행하지 못해 신호를 만들 수 없었습니다");
				}
			}
			if (report.complete() && signalDate.equals(report.state().settledThroughDate())) {
				ready.add(strategy);
			} else {
				gaps.putIfAbsent(strategy, "정산이 신호일 " + signalDate + "까지 끝나지 않았습니다");
			}
		}

		// 같은 신호일의 대기 신호를 이미 저장한 전략은 다시 판정하지 않는다. 재판정하면 조회 장애로 덜 판정된 신호 집합이 정상 실행의
		// 신호를 덮어쓰고(signals CSV에는 두 실행이 섞임), 게이트나 스캔이 실패한 재실행은 신호가 저장된 날에 공백 행을 남긴다.
		// 저장했는지는 상태의 대기 신호가 있거나 runs.csv에 SIGNALS_SAVED 행이 있는지로 본다 (후자는 0건을 저장한 날을 구분하려는 표시다.
		// 대기 신호를 저장한 직후 표시를 쓰기 전에 죽으면 신호가 있는 날은 전자가, 0건인 날은 다시 판정하는 것이 막는다)
		Set<String> alreadySaved = new HashSet<>();
		for (String strategy : ready) {
			if (pendingCount(reports.get(strategy).state(), signalDate) > 0 || signalsSavedMarkerExists(strategy, signalDate)) {
				alreadySaved.add(strategy);
			}
		}
		boolean needsScan = ready.stream().anyMatch(s -> !alreadySaved.contains(s));

		List<String> warnings = new ArrayList<>();
		RuntimeException failure = null;
		String sharedGap = null;
		SignalScan scan = null;
		if (needsScan) {
			try {
				TargetUniverse universe = universeService.build();
				SignalGate.Decision gate = SignalGate.common(signalDate, universe.rankedAt(), now);
				if (gate.open()) {
					scan = scanService.scan(universe, signalDate);
				} else {
					sharedGap = gate.reason();
				}
			} catch (RuntimeException e) {
				failure = e;
				sharedGap = "신호 판정 실패: " + e.getMessage();
			}
			if (scan != null) {
				// 이 기록이 실패해도(엑셀이 파일을 열어 둔 경우 등) 이미 판정한 신호의 저장을 막지 않고 경고로 알린다
				try {
					new ScanRowLog(settings.recordsDirectory().resolve("scan_rows.csv")).append(scan);
				} catch (RuntimeException e) {
					log.warn("[가상매매] scan_rows.csv 기록에 실패했습니다: {}", e.getMessage());
					warnings.add("scan_rows.csv 기록에 실패했습니다 (다른 프로그램이 파일을 열어 두었는지 확인): " + e.getMessage());
				}
			}
		}

		Map<String, RunReport.StrategyResult> results = new LinkedHashMap<>();
		for (String strategy : Strategies.ALL) {
			PaperLedger.Report report = reports.get(strategy);
			if (alreadySaved.contains(strategy)) {
				results.putIfAbsent(strategy, new RunReport.StrategyResult(strategy, report, null,
					pendingCount(report.state(), signalDate), List.of(), true));
				recorded.add(strategy);
				continue;
			}
			String gap = ready.contains(strategy) ? sharedGap : gaps.get(strategy);
			if (gap == null) {
				if (scan == null) {
					throw new IllegalStateException("내부 오류: 신호 판정 결과도 공백 사유도 없습니다 (전략 " + strategy + ")");
				}
				if (Strategies.INSTITUTION.equals(strategy)) {
					SignalGate.Decision institutionGate = SignalGate.institution(signalDate, scan.rows());
					gap = institutionGate.open() ? null : institutionGate.reason();
				}
			}
			if (gap != null) {
				ledger.recordRun(strategy, SIGNAL_GAP, signalDate, oneLine(gap));
				recorded.add(strategy);
				results.putIfAbsent(strategy, new RunReport.StrategyResult(strategy, report, gap, 0, List.of(), false));
				continue;
			}
			PendingSignals.Built built = PendingSignals.build(strategy, scan);
			ledger.recordPending(strategy, signalDate, built.signals());
			recorded.add(strategy);
			ledger.recordRun(strategy, SIGNALS_SAVED, signalDate, "pending=" + built.signals().size());
			results.putIfAbsent(strategy, new RunReport.StrategyResult(strategy, report, null, built.signals().size(),
				built.missingBasis(), false));
		}

		RunReport.Message message = RunReport.format(signalDate, List.copyOf(results.values()), scan, warnings);
		log.info("[가상매매] 실행 끝\n{}", message.text());
		notifier.sendNow(message.text());
		if (failure != null) {
			throw failure;
		}
	}

	// 결과를 기록하지 못한 전략에 공백을 남긴다. 기록 자체가 실패한 상황일 수 있어서 여기서의 실패는 삼키고 로그만 남긴다
	private void recordGapsBestEffort(PaperLedger ledger, LocalDate signalDate, Set<String> recorded, RuntimeException cause) {
		for (String strategy : Strategies.ALL) {
			if (recorded.contains(strategy)) {
				continue;
			}
			try {
				ledger.recordRun(strategy, SIGNAL_GAP, signalDate, oneLine("실행 중 오류: " + cause.getMessage()));
			} catch (RuntimeException e) {
				log.warn("[가상매매] 전략 {}의 공백을 기록하지 못했습니다: {}", strategy, e.getMessage());
			}
		}
	}

	// 원장 설정 파일이 없는 폴더는 처음 쓰는 폴더이거나 작업 폴더와 설정 경로가 틀어진 것이다. 후자인데 조용히 빈 원장을 시작하면
	// 기존 보유 포지션과 대기 신호가 무시된 채 기록이 다른 곳에 쌓이므로, 처음 가동일 때만 --init-ledger로 명시하게 한다
	private void requireExistingLedgerUnlessInitializing(boolean initLedger) {
		Path config = settings.stateDirectory().resolve(LEDGER_CONFIG_FILE);
		if (!initLedger && !Files.exists(config)) {
			throw new IllegalStateException("원장 설정 파일이 없습니다: " + config + ". 처음 가동이면 " + INIT_LEDGER_ARG
				+ "를 붙여 한 번 실행하세요. 이미 가동 중이었다면 작업 폴더나 damdam.paper.* 경로가 바뀐 것입니다.");
		}
	}

	// runs.csv의 열: run_at, strategy, settle_date, status, ... (앞 네 열은 키라 쉼표가 들어갈 수 없다)
	private boolean signalsSavedMarkerExists(String strategy, LocalDate signalDate) {
		Path runs = settings.recordsDirectory().resolve("runs.csv");
		if (!Files.exists(runs)) {
			return false;
		}
		try {
			return Files.readAllLines(runs).stream().skip(1).map(line -> line.split(",", 5))
				.anyMatch(cells -> cells.length >= 4 && cells[1].equals(strategy) && cells[2].equals(signalDate.toString())
					&& cells[3].equals(SIGNALS_SAVED));
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException("실행 기록을 읽지 못했습니다: " + runs, e);
		}
	}

	private static int pendingCount(PaperStrategyState state, LocalDate signalDate) {
		return (int) state.pending().stream().filter(p -> p.signalDate().equals(signalDate)).count();
	}

	// 이 전략이 정산할 첫 거래일 후보. 처음이면 신호일 하루만 본다 (가진 것이 없어 밀린 날이 없다)
	private static LocalDate settleFrom(PaperStrategyState state, LocalDate signalDate) {
		return state.settledThroughDate() == null ? signalDate : state.settledThroughDate().plusDays(1);
	}

	// 기록 파일의 note 열에 쉼표나 줄바꿈이 들어가면 CSV가 깨지므로 공백으로 바꾼다
	private static String oneLine(String text) {
		return text.replaceAll("[,\"\\r\\n]+", " ");
	}
}
