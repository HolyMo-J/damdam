package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingPage;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.SignalInputService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import com.damdam.bot.stocks.StockWarning;
import com.damdam.bot.stocks.StockWarningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// paper-probe 프로필로 실행할 때만 동작하는 3단계 가상매매용 실측 조사 도구. 조회만 하고 주문은 하지 않는다.
// 명세만으로는 알 수 없는 것을 실제 응답으로 확인하려고 만들었고, 시각이 지나면서 값이 어떻게 바뀌는지 보는 것이 목적이다.
// 사람이 시각마다 직접 돌릴 수 없으므로 한 번 띄워 두면 일정 간격으로 반복하고(--repeat-minutes, --repeat-count),
// 두 번째 회차부터는 직전 회차와 달라진 값을 [변화]로 보여준다. 확인 항목은 docs/todo.md "확인 필요" 참고.
//   1) rankings duration=1d의 집계 기준 시각(rankedAt)과 내용이 시각에 따라 어떻게 달라지는지
//   2) excludeInvestmentCaution 옵션이 실제로 어떤 종목을 빼는지 (켠 결과와 끈 결과의 차이 종목의 유의사항 표시)
//   3) 일봉 count=100이 실제로 몇 개 오는지, timestamp 형식, 0번 봉이 오늘 날짜인지와 그 봉의 종가와 거래량
//   4) 기관 매매동향의 최신 기록 날짜와 updatedAt, 일봉 0번 봉 날짜와의 일치 여부
//   5) 옵션을 켠 순위 100개 전부의 매수 유의사항 유형별 집계 (옵션이 빼지 않는 유형이 상위 100개 안에 실제로 있는지)
//   6) 표본 종목의 순위 항목(현재가, 기준가, 등락률, 거래량)과 일봉 0번 봉의 종가, 거래량 대조 (순위와 일봉의 집계 범위가 같은지)
// 결과는 콘솔 로그와 ../records/probe/paper-probe.log(git 제외 폴더)에 같은 내용으로 남고 실행할 때마다 이어 붙인다.
// 계좌 식별값이나 토큰은 다루지 않는다.
// 사용법: ./gradlew bootRun --args='--spring.profiles.active=paper-probe [종목코드 ...] [--repeat-minutes=10 --repeat-count=15]'
//   종목코드를 안 주면 첫 회차 순위(옵션 켠 결과) 1, 2위 종목을 표본으로 정해 이후 회차에도 같은 종목을 쓴다. 반복 옵션을 안 주면 1회만 돈다.
//   유의사항 집계(100번 조회)는 첫 회차에만 한다. 각 회차가 끝날 때마다 결과 파일에 바로 이어 붙여서 중간에 끊어도 앞 회차 결과는 남는다
@Component
@Profile("paper-probe")
public class PaperProbeRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(PaperProbeRunner.class);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Path REPORT = Path.of("..", "records", "probe", "paper-probe.log");
	// 차이 종목의 이름과 유의사항을 찾는 상한. 경고 조회는 종목마다 한 번씩이라 많으면 오래 걸린다
	private static final int MAX_DIFF_LOOKUPS = 30;
	private static final long PAUSE_BETWEEN_CALLS_MS = 300;
	private static final int DEFAULT_SAMPLE_COUNT = 2;
	private static final int DEFAULT_INTERVAL_MINUTES = 10;
	private static final int MAX_INTERVAL_MINUTES = 120;
	private static final int MAX_ROUNDS = 200;

	// 회차 사이에 기다리는 방법. 테스트에서 실제로 기다리지 않도록 바꿔 끼울 수 있게 뺐다
	@FunctionalInterface
	interface Waiter {
		void await(long millis) throws InterruptedException;
	}

	private final RankingService rankingService;
	private final StockInfoService stockInfoService;
	private final StockWarningService stockWarningService;
	private final SignalInputService signalInputService;
	private final Path report;
	private final long pauseMs;
	private final Waiter waiter;

	@Autowired
	public PaperProbeRunner(RankingService rankingService, StockInfoService stockInfoService,
							StockWarningService stockWarningService, SignalInputService signalInputService) {
		this(rankingService, stockInfoService, stockWarningService, signalInputService, REPORT, PAUSE_BETWEEN_CALLS_MS, Thread::sleep);
	}

	PaperProbeRunner(RankingService rankingService, StockInfoService stockInfoService, StockWarningService stockWarningService,
					 SignalInputService signalInputService, Path report, long pauseMs) {
		this(rankingService, stockInfoService, stockWarningService, signalInputService, report, pauseMs, millis -> { });
	}

	// 테스트에서 실제 저장소 폴더를 건드리거나 기다리지 않도록 결과 파일과 호출 간격과 회차 사이 대기를 바꿔 끼울 수 있게 둔다
	PaperProbeRunner(RankingService rankingService, StockInfoService stockInfoService, StockWarningService stockWarningService,
					 SignalInputService signalInputService, Path report, long pauseMs, Waiter waiter) {
		this.rankingService = rankingService;
		this.stockInfoService = stockInfoService;
		this.stockWarningService = stockWarningService;
		this.signalInputService = signalInputService;
		this.report = report;
		this.pauseMs = pauseMs;
		this.waiter = waiter;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		int intervalMinutes = intArg(args, "--repeat-minutes=", DEFAULT_INTERVAL_MINUTES, 1, MAX_INTERVAL_MINUTES);
		int rounds = intArg(args, "--repeat-count=", 1, 1, MAX_ROUNDS);
		List<String> symbols = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();

		Map<String, String> previous = null;
		String previousTime = null;
		for (int round = 1; round <= rounds; round++) {
			if (round > 1) {
				waiter.await(intervalMinutes * 60_000L);
			}
			List<String> lines = new ArrayList<>();
			Map<String, String> snapshot = new LinkedHashMap<>();
			String startedAt = ZonedDateTime.now(KST).format(KST_TIME);
			lines.add("=== 조사 시작 " + startedAt + " (KST)" + (rounds > 1 ? " 회차 " + round + "/" + rounds : "") + " ===");

			List<Ranking> ranked = rankingSection(lines, snapshot);
			if (round == 1) {
				warningCensus(lines, ranked);
			}

			if (symbols.isEmpty() && !ranked.isEmpty()) {
				symbols = ranked.stream().limit(DEFAULT_SAMPLE_COUNT).map(Ranking::symbol).toList();
				lines.add("[표본] 종목코드를 안 줘서 순위(옵션 켠 결과) 상위 " + symbols.size() + "개를 씁니다" + (rounds > 1 ? "(이후 회차도 같은 종목)" : "") + ": " + symbols);
			}
			for (String symbol : symbols) {
				Optional<Ranking> rankingEntry = ranked.stream().filter(r -> r.symbol().equals(symbol)).findFirst();
				Optional<LocalDate> latestCandleDate = candleSection(lines, symbol, rankingEntry, snapshot);
				flowSection(lines, symbol, latestCandleDate, snapshot);
				pause();
			}

			if (previous != null) {
				changeLines(lines, previousTime, previous, snapshot);
			}
			lines.add("=== 조사 끝 " + ZonedDateTime.now(KST).format(KST_TIME) + " (KST) ===");
			lines.forEach(line -> log.info("[조사] {}", line));
			writeReport(lines);

			previous = snapshot;
			previousTime = startedAt;
		}
	}

	// 관측값을 이름표와 함께 기록해 두었다가 다음 회차와 비교한다. 가상매매 코드에서는 쓰기 호출 토큰이 금지돼 있어 merge를 쓴다
	private static void observe(Map<String, String> snapshot, String label, String value) {
		snapshot.merge(label, value, (old, latest) -> latest);
	}

	// 직전 회차와 달라진 값만 보여준다. 값이 언제 확정되는지(더 이상 안 바뀌는 시각)를 찾는 것이 목적이다
	static void changeLines(List<String> lines, String previousTime, Map<String, String> previous, Map<String, String> current) {
		List<String> changes = new ArrayList<>();
		current.forEach((label, value) -> {
			String before = previous.get(label);
			if (before == null) {
				changes.add("  " + label + ": (이전 회차에 없음) -> " + value);
			} else if (!before.equals(value)) {
				changes.add("  " + label + ": " + before + " -> " + value);
			}
		});
		previous.forEach((label, value) -> {
			if (!current.containsKey(label)) {
				changes.add("  " + label + ": " + value + " -> (이번 회차에 관측 못 함)");
			}
		});
		if (changes.isEmpty()) {
			lines.add("[변화] 직전 회차(" + previousTime + ") 대비 달라진 값 없음");
		} else {
			lines.add("[변화] 직전 회차(" + previousTime + ") 대비 달라진 값 " + changes.size() + "개");
			lines.addAll(changes);
		}
	}

	// "--이름=숫자" 인자를 읽는다. 없거나 숫자가 아니면 기본값, 범위를 벗어나면 가까운 끝값으로 맞춘다
	static int intArg(String[] args, String prefix, int defaultValue, int min, int max) {
		for (String arg : args) {
			if (arg.startsWith(prefix)) {
				try {
					return Math.max(min, Math.min(max, Integer.parseInt(arg.substring(prefix.length()).trim())));
				} catch (NumberFormatException e) {
					return defaultValue;
				}
			}
		}
		return defaultValue;
	}

	// 1)과 2). 옵션을 끈 순위와 켠 순위를 나란히 받아 차이를 본다. 켠 결과 목록은 표본 종목 선택에도 쓴다
	private List<Ranking> rankingSection(List<String> lines, Map<String, String> snapshot) throws InterruptedException {
		try {
			RankingPage off = rankingService.getMarketTradingAmountPage("KR", "1d", 100, false);
			pause();
			RankingPage on = rankingService.getMarketTradingAmountPage("KR", "1d", 100, true);

			lines.add("[순위] KR MARKET_TRADING_AMOUNT duration=1d count=100");
			lines.add("  excludeInvestmentCaution=false: " + describe(off));
			lines.add("  excludeInvestmentCaution=true : " + describe(on));

			List<Ranking> onlyOff = onlyIn(off.rankings(), on.rankings());
			List<Ranking> onlyOn = onlyIn(on.rankings(), off.rankings());
			lines.add("  옵션이 뺀 종목(끈 결과에만 있음): " + onlyOff.size() + "개");
			describeDifference(lines, onlyOff);
			lines.add("  옵션을 켜서 새로 채워진 종목(켠 결과에만 있음): " + onlyOn.size() + "개 " + onlyOn.stream().map(Ranking::symbol).toList());
			observe(snapshot, "순위 rankedAt(옵션 켠 결과)", formatRankedAt(on.rankedAt()));
			if (!on.rankings().isEmpty()) {
				Ranking top = on.rankings().get(0);
				observe(snapshot, "순위 1위 종목과 거래대금", top.symbol() + " " + top.tradingAmount());
			}
			return on.rankings();
		} catch (RuntimeException e) {
			lines.add("[순위] 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			return List.of();
		}
	}

	// 5). 순위 목록 전체의 유의사항을 유형별로 센다. 옵션을 켠 순위 안에 투자경고나 단기과열 종목이 있다면 옵션이 그 유형을 빼지 않는다는 뜻이다
	private void warningCensus(List<String> lines, List<Ranking> ranked) throws InterruptedException {
		if (ranked.isEmpty()) {
			return;
		}
		Map<String, List<String>> symbolsByType = new TreeMap<>();
		int failed = 0;
		int withAny = 0;
		for (int i = 0; i < ranked.size(); i++) {
			if (i > 0) {
				pause();
			}
			String symbol = ranked.get(i).symbol();
			try {
				List<StockWarning> warnings = stockWarningService.getWarnings(symbol);
				if (!warnings.isEmpty()) {
					withAny++;
				}
				for (String type : warnings.stream().map(StockWarning::warningType).distinct().toList()) {
					symbolsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(symbol);
				}
			} catch (RuntimeException e) {
				failed++;
			}
		}
		lines.add("[유의사항 집계] 옵션을 켠 순위 " + ranked.size() + "개 중 유의사항이 있는 종목 " + withAny + "개, 조회 실패 " + failed + "개");
		Set<String> flagged = new LinkedHashSet<>();
		symbolsByType.forEach((type, symbols) -> {
			// VI는 순간적으로 붙었다 사라지는 표시라 개수만 보고, 나머지 유형은 종목까지 보여준다
			boolean transientType = type.startsWith("VI_");
			lines.add("  " + type + " " + symbols.size() + "개" + (transientType ? "" : " " + symbols));
			if (!transientType) {
				flagged.addAll(symbols);
			}
		});
		if (symbolsByType.isEmpty()) {
			lines.add("  유의사항이 있는 종목이 없습니다");
		}
		if (!flagged.isEmpty()) {
			try {
				String names = stockInfoService.getStocks(flagged.stream().limit(MAX_DIFF_LOOKUPS).toList()).stream()
					.map(info -> info.symbol() + " " + info.name())
					.collect(Collectors.joining(", "));
				lines.add("  종목 이름: " + names);
			} catch (RuntimeException e) {
				lines.add("  종목 이름 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			}
		}
	}

	private void describeDifference(List<String> lines, List<Ranking> onlyOff) throws InterruptedException {
		if (onlyOff.isEmpty()) {
			return;
		}
		List<Ranking> targets = onlyOff.stream().limit(MAX_DIFF_LOOKUPS).toList();
		if (targets.size() < onlyOff.size()) {
			lines.add("    (상한 " + MAX_DIFF_LOOKUPS + "개까지만 이름과 유의사항을 찾습니다)");
		}
		Map<String, StockInfo> infos;
		try {
			infos = stockInfoService.getStocks(targets.stream().map(Ranking::symbol).toList()).stream()
				.collect(Collectors.toMap(StockInfo::symbol, Function.identity(), (first, second) -> first));
		} catch (RuntimeException e) {
			lines.add("    종목 정보 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			infos = Map.of();
		}
		for (Ranking ranking : targets) {
			pause();
			StockInfo info = infos.get(ranking.symbol());
			String name = info == null ? "이름 확인 못 함" : info.name();
			String kind = info == null ? "" : " " + info.market() + " " + info.securityType();
			lines.add("    " + ranking.rank() + "위 " + ranking.symbol() + " " + name + kind + " | 유의사항: " + warningsOf(ranking.symbol()));
		}
	}

	private String warningsOf(String symbol) {
		try {
			List<StockWarning> warnings = stockWarningService.getWarnings(symbol);
			if (warnings.isEmpty()) {
				return "없음";
			}
			return warnings.stream()
				.map(w -> w.warningType() + "(" + w.startDate() + "~" + w.endDate() + ")")
				.collect(Collectors.joining(", "));
		} catch (RuntimeException e) {
			return "조회 실패 " + e.getMessage();
		}
	}

	// 3). 신호 판정이 실제로 받는 일봉(SignalInputService)을 그대로 조회해 본다
	private Optional<LocalDate> candleSection(List<String> lines, String symbol, Optional<Ranking> rankingEntry, Map<String, String> snapshot) {
		try {
			List<Candle> candles = signalInputService.getDailyCandles(symbol);
			lines.add("[일봉] " + symbol + ": 요청 " + SignalInputService.CANDLE_COUNT + "봉, 받은 봉 " + candles.size() + "개");
			for (int i = 0; i < Math.min(2, candles.size()); i++) {
				Candle c = candles.get(i);
				lines.add("  " + i + "번 봉 timestamp=" + c.timestamp() + " 종가=" + c.closePrice() + " 거래량=" + c.volume());
			}
			LocalDate latest = DailyCandles.dateOf(candles.get(0));
			LocalDate today = LocalDate.now(KST);
			lines.add("  0번 봉 날짜 " + latest + ", 오늘(KST) " + today + " -> " + (latest.equals(today) ? "오늘 봉이 0번에 있음 (장중 잠정이거나 확정)" : "오늘 봉이 아직 없음"));
			compareWithRanking(lines, candles, rankingEntry);
			observe(snapshot, symbol + " 일봉 0번 날짜", latest.toString());
			observe(snapshot, symbol + " 일봉 0번 종가", candles.get(0).closePrice());
			observe(snapshot, symbol + " 일봉 0번 거래량", candles.get(0).volume());
			rankingEntry.ifPresent(r -> observe(snapshot, symbol + " 순위 거래량", r.tradingVolume()));
			return Optional.of(latest);
		} catch (RuntimeException e) {
			lines.add("[일봉] " + symbol + " 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			return Optional.empty();
		}
	}

	// 6). 순위 항목과 일봉 0번 봉을 나란히 놓는다. 순위 현재가가 일봉 종가와 다르거나 거래량 배율이 1이 아니면 집계 범위가 다르다는 정황이다
	// (원인은 이 도구로 단정하지 못한다. 예를 들어 순위는 통합 시세이고 일봉은 정규장만 담을 수 있는지는 확인하지 못했다)
	private static void compareWithRanking(List<String> lines, List<Candle> candles, Optional<Ranking> rankingEntry) {
		if (rankingEntry.isEmpty()) {
			lines.add("  순위 목록(옵션 켠 결과)에 없는 종목이라 순위 항목과의 대조는 생략합니다");
			return;
		}
		Ranking r = rankingEntry.get();
		Candle today = candles.get(0);
		lines.add("  순위 항목: " + r.rank() + "위 lastPrice=" + r.price().lastPrice() + " basePrice=" + r.price().basePrice()
			+ " changeRate=" + r.price().changeRate() + " tradingVolume=" + r.tradingVolume() + " tradingAmount=" + r.tradingAmount());
		lines.add("  현재가 대조: 순위 lastPrice=" + r.price().lastPrice() + " vs 일봉 0번 종가=" + today.closePrice()
			+ " -> " + (new BigDecimal(r.price().lastPrice()).compareTo(new BigDecimal(today.closePrice())) == 0 ? "같음" : "다름"));
		if (candles.size() >= 2) {
			BigDecimal close0 = new BigDecimal(today.closePrice());
			BigDecimal close1 = new BigDecimal(candles.get(1).closePrice());
			if (close1.signum() != 0) {
				lines.add("  등락률 대조: 순위 changeRate=" + r.price().changeRate() + " vs 일봉 종가 기준 " + close0.subtract(close1).divide(close1, 4, RoundingMode.HALF_UP)
					+ " (순위 basePrice=" + r.price().basePrice() + ", 일봉 1번 종가=" + close1.toPlainString() + ")");
			}
		}
		BigDecimal rankingVolume = new BigDecimal(r.tradingVolume());
		BigDecimal candleVolume = new BigDecimal(today.volume());
		String ratio = rankingVolume.signum() == 0 ? "순위 거래량 0" : candleVolume.divide(rankingVolume, 4, RoundingMode.HALF_UP).toPlainString();
		lines.add("  거래량 대조: 일봉 0번 " + candleVolume.toPlainString() + " / 순위 " + rankingVolume.toPlainString() + " = " + ratio + " (1에 가까우면 같은 범위)");
	}

	// 4). 기관 매매동향의 최신 기록 날짜와 갱신 시각을 본다. 저녁 확정 전후로 updatedAt과 순매수량이 어떻게 바뀌는지가 핵심이다
	private void flowSection(List<String> lines, String symbol, Optional<LocalDate> latestCandleDate, Map<String, String> snapshot) {
		try {
			List<InvestorTradingRecord> records = signalInputService.getInstitutionFlows(symbol);
			lines.add("[매매동향] " + symbol + ": 요청 " + SignalInputService.FLOW_COUNT + "건, 받은 기록 " + records.size() + "건");
			for (int i = 0; i < Math.min(3, records.size()); i++) {
				InvestorTradingRecord r = records.get(i);
				lines.add("  " + i + "번 기록 date=" + r.date() + " updatedAt(KST)=" + formatKst(r.updatedAt()) + " 기관순매수=" + r.institutionNetBuyVolume());
			}
			if (!records.isEmpty()) {
				InvestorTradingRecord latest = records.get(0);
				observe(snapshot, symbol + " 매매동향 최신 날짜", latest.date().toString());
				observe(snapshot, symbol + " 매매동향 updatedAt(KST)", formatKst(latest.updatedAt()));
				observe(snapshot, symbol + " 매매동향 기관순매수", latest.institutionNetBuyVolume().toPlainString());
			}
			if (!records.isEmpty() && latestCandleDate.isPresent()) {
				LocalDate flowDate = records.get(0).date();
				lines.add("  최신 기록 날짜 " + flowDate + " vs 일봉 0번 날짜 " + latestCandleDate.get() + " -> " + (flowDate.equals(latestCandleDate.get()) ? "같음" : "다름"));
			}
		} catch (RuntimeException e) {
			lines.add("[매매동향] " + symbol + " 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
		}
	}

	private void pause() throws InterruptedException {
		if (pauseMs > 0) {
			Thread.sleep(pauseMs);
		}
	}

	private void writeReport(List<String> lines) {
		try {
			Files.createDirectories(report.toAbsolutePath().getParent());
			Files.write(report, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			log.info("[조사] 결과를 {}에 이어 붙였습니다.", report.toAbsolutePath().normalize());
		} catch (IOException e) {
			log.warn("[조사] 결과 파일을 쓰지 못했습니다(콘솔 로그는 남았습니다): {}", e.getMessage());
		}
	}

	private static String describe(RankingPage page) {
		String first = page.rankings().isEmpty() ? "1위 없음" : firstOf(page.rankings().get(0));
		return "rankedAt(KST)=" + formatRankedAt(page.rankedAt()) + ", 항목 " + page.rankings().size() + "개, " + first;
	}

	private static String firstOf(Ranking r) {
		return "1위 " + r.symbol() + " 거래대금 " + r.tradingAmount() + " 등락률 " + r.price().changeRate();
	}

	static String formatRankedAt(String rankedAt) {
		if (rankedAt == null) {
			return "없음(집계 없음)";
		}
		return OffsetDateTime.parse(rankedAt).atZoneSameInstant(KST).format(KST_TIME);
	}

	static String formatKst(Instant instant) {
		return instant == null ? "없음" : instant.atZone(KST).format(KST_TIME);
	}

	// first에 있고 second에는 없는 종목. first의 순위 순서를 지킨다
	static List<Ranking> onlyIn(List<Ranking> first, List<Ranking> second) {
		Set<String> other = new HashSet<>();
		second.forEach(r -> other.add(r.symbol()));
		return first.stream().filter(r -> !other.contains(r.symbol())).toList();
	}
}
