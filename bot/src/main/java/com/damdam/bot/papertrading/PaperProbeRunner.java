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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// paper-probe 프로필로 실행할 때만 동작하는 3단계 가상매매용 실측 조사 도구. 조회만 하고 주문은 하지 않는다.
// 명세만으로는 알 수 없는 것을 실제 응답으로 확인하려고 만들었고, 시각을 바꿔 여러 번 돌려 결과를 비교하는 것이 목적이다
// (예: 장 시작 전 08:30, 마감 직후 15:35, 저녁 20:05, 다음 거래일 08:30). 확인 항목은 docs/todo.md "확인 필요" 참고.
//   1) rankings duration=1d의 집계 기준 시각(rankedAt)과 내용이 시각에 따라 어떻게 달라지는지
//   2) excludeInvestmentCaution 옵션이 실제로 어떤 종목을 빼는지 (켠 결과와 끈 결과의 차이 종목의 유의사항 표시)
//   3) 일봉 count=100이 실제로 몇 개 오는지, timestamp 형식, 0번 봉이 오늘 날짜인지와 그 봉의 종가와 거래량
//   4) 기관 매매동향의 최신 기록 날짜와 updatedAt, 일봉 0번 봉 날짜와의 일치 여부
// 결과는 콘솔 로그와 ../records/probe/paper-probe.log(git 제외 폴더)에 같은 내용으로 남고 실행할 때마다 이어 붙인다.
// 계좌 식별값이나 토큰은 다루지 않는다.
// 사용법: ./gradlew bootRun --args='--spring.profiles.active=paper-probe [종목코드 ...]' (종목코드를 안 주면 순위 1, 2위 종목)
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

	private final RankingService rankingService;
	private final StockInfoService stockInfoService;
	private final StockWarningService stockWarningService;
	private final SignalInputService signalInputService;
	private final Path report;
	private final long pauseMs;

	@Autowired
	public PaperProbeRunner(RankingService rankingService, StockInfoService stockInfoService,
							StockWarningService stockWarningService, SignalInputService signalInputService) {
		this(rankingService, stockInfoService, stockWarningService, signalInputService, REPORT, PAUSE_BETWEEN_CALLS_MS);
	}

	// 테스트에서 실제 저장소 폴더를 건드리거나 기다리지 않도록 결과 파일과 호출 간격을 바꿔 끼울 수 있게 둔다
	PaperProbeRunner(RankingService rankingService, StockInfoService stockInfoService, StockWarningService stockWarningService,
					 SignalInputService signalInputService, Path report, long pauseMs) {
		this.rankingService = rankingService;
		this.stockInfoService = stockInfoService;
		this.stockWarningService = stockWarningService;
		this.signalInputService = signalInputService;
		this.report = report;
		this.pauseMs = pauseMs;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		List<String> lines = new ArrayList<>();
		lines.add("=== 조사 시작 " + ZonedDateTime.now(KST).format(KST_TIME) + " (KST) ===");

		List<Ranking> ranked = rankingSection(lines);

		List<String> symbols = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
		if (symbols.isEmpty()) {
			symbols = ranked.stream().limit(DEFAULT_SAMPLE_COUNT).map(Ranking::symbol).toList();
			lines.add("[표본] 종목코드를 안 줘서 순위(옵션 켠 결과) 상위 " + symbols.size() + "개를 씁니다: " + symbols);
		}
		for (String symbol : symbols) {
			Optional<LocalDate> latestCandleDate = candleSection(lines, symbol);
			flowSection(lines, symbol, latestCandleDate);
			pause();
		}

		lines.add("=== 조사 끝 " + ZonedDateTime.now(KST).format(KST_TIME) + " (KST) ===");
		lines.forEach(line -> log.info("[조사] {}", line));
		writeReport(lines);
	}

	// 1)과 2). 옵션을 끈 순위와 켠 순위를 나란히 받아 차이를 본다. 켠 결과 목록은 표본 종목 선택에도 쓴다
	private List<Ranking> rankingSection(List<String> lines) throws InterruptedException {
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
			return on.rankings();
		} catch (RuntimeException e) {
			lines.add("[순위] 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			return List.of();
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
	private Optional<LocalDate> candleSection(List<String> lines, String symbol) {
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
			return Optional.of(latest);
		} catch (RuntimeException e) {
			lines.add("[일봉] " + symbol + " 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			return Optional.empty();
		}
	}

	// 4). 기관 매매동향의 최신 기록 날짜와 갱신 시각을 본다. 저녁 확정 전후로 updatedAt과 순매수량이 어떻게 바뀌는지가 핵심이다
	private void flowSection(List<String> lines, String symbol, Optional<LocalDate> latestCandleDate) {
		try {
			List<InvestorTradingRecord> records = signalInputService.getInstitutionFlows(symbol);
			lines.add("[매매동향] " + symbol + ": 요청 " + SignalInputService.FLOW_COUNT + "건, 받은 기록 " + records.size() + "건");
			for (int i = 0; i < Math.min(3, records.size()); i++) {
				InvestorTradingRecord r = records.get(i);
				lines.add("  " + i + "번 기록 date=" + r.date() + " updatedAt(KST)=" + formatKst(r.updatedAt()) + " 기관순매수=" + r.institutionNetBuyVolume());
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
