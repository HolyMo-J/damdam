package com.damdam.bot.backtest;

import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingPage;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// export-universe 프로필로 실행할 때만 동작. 국내 거래대금 상위 종목군(대형주/중형주 후보)의 캔들을 한 번에 수집한다.
// 거래대금 랭킹에는 ETF/ETN(레버리지·인버스 상품 등)도 섞여 있어서, 종목 정보를 다시 조회해
// 일반 보통주(STOCK, isCommonShare)만 걸러낸다.
// 사용법: ./gradlew bootRun --args='--spring.profiles.active=export-universe [count]' (기본 count=30)
// 옵션 (모두 생략하면 기존 동작 그대로): --duration=1y (순위 집계 기간), --exclude-caution=true (순위의 투자유의 종목 제외),
//   --out-dir=../analysis/data (저장 폴더). 3단계 대상 종목군과 같은 정의로 받으려면 --duration=1d --exclude-caution=false
//   (docs/strategy.md "대상 종목군"), 종목 수 제한 없이 걸러진 전부는 count에 100을 준다. 선택한 종목 목록은 저장 폴더의 _universe.csv에 남는다
@Component
@Profile("export-universe")
public class UniverseExportRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(UniverseExportRunner.class);
	private static final int DEFAULT_COUNT = 30;
	private static final String DEFAULT_DURATION = "1y";
	private static final String DEFAULT_OUT_DIR = "../analysis/data";
	private static final int RANKING_POOL_SIZE = 100; // ETF 등을 걸러내고도 count를 채울 수 있도록 넉넉히 조회
	private static final long PAUSE_BETWEEN_SYMBOLS_MS = 300;

	private final RankingService rankingService;
	private final StockInfoService stockInfoService;
	private final CandleHistoryExporter exporter;

	public UniverseExportRunner(RankingService rankingService, StockInfoService stockInfoService,
			CandleHistoryExporter exporter) {
		this.rankingService = rankingService;
		this.stockInfoService = stockInfoService;
		this.exporter = exporter;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		int count = parseCount(args).orElse(DEFAULT_COUNT);
		String duration = parseOption(args, "--duration=").orElse(DEFAULT_DURATION);
		boolean excludeCaution = parseBooleanOption(args, "--exclude-caution=").orElse(true);
		Path outDir = Path.of(parseOption(args, "--out-dir=").orElse(DEFAULT_OUT_DIR));

		// 기본은 최근 1년 국내 거래대금 상위, 투자유의 종목 제외 (복권형 소형주 배제 목적, docs/strategy.md 참고)
		RankingPage rankingPage = rankingService.getMarketTradingAmountPage("KR", duration, RANKING_POOL_SIZE, excludeCaution);
		List<Ranking> rankings = rankingPage.rankings();
		Map<String, StockInfo> infoBySymbol = stockInfoService.getStocks(rankings.stream().map(Ranking::symbol).toList())
			.stream()
			.collect(Collectors.toMap(StockInfo::symbol, Function.identity()));

		List<Ranking> stocksOnly = rankings.stream()
			.filter(r -> isOrdinaryStock(infoBySymbol.get(r.symbol())))
			.limit(count)
			.toList();

		log.info("[종목군 수집] 순위 기준 {}(투자유의 제외 {}, 집계 시각 {}) 상위 {}종목 중 ETF/ETN 등을 제외하고 보통주 {}종목 확보. 캔들 수집을 시작합니다. 저장 폴더 {}",
			duration, excludeCaution, rankingPage.rankedAt(), rankings.size(), stocksOnly.size(), outDir);
		writeUniverseList(outDir, stocksOnly, infoBySymbol, duration, excludeCaution, rankingPage.rankedAt());

		for (Ranking ranking : stocksOnly) {
			StockInfo info = infoBySymbol.get(ranking.symbol());
			log.info("[종목군 수집] {}위 {}({}) 거래대금 {}", ranking.rank(), ranking.symbol(), info.name(), ranking.tradingAmount());
			Path outputCsv = outDir.resolve(ranking.symbol() + "_daily.csv");
			exporter.exportDailyHistory(ranking.symbol(), outputCsv);
			Thread.sleep(PAUSE_BETWEEN_SYMBOLS_MS);
		}

		log.info("[종목군 수집] 전체 {}종목 수집 완료.", stocksOnly.size());
	}

	// 어떤 순위 기준으로 어느 종목을 골랐는지 캔들 CSV 옆에 남긴다 (분석 쪽이 종목 목록과 순위, 집계 시각을 다시 확인할 수 있게).
	// 파일 이름이 _daily.csv로 끝나지 않아 캔들 파일과 섞이지 않는다
	private void writeUniverseList(Path outDir, List<Ranking> selected, Map<String, StockInfo> infoBySymbol,
			String duration, boolean excludeCaution, String rankedAt) {
		StringBuilder sb = new StringBuilder("rank,symbol,name,trading_amount,duration,exclude_caution,ranked_at\n");
		for (Ranking ranking : selected) {
			// 종목명에 쉼표가 들어가도 열이 밀리지 않게 따옴표로 감싼다 (따옴표는 두 번 쓴다)
			String name = infoBySymbol.get(ranking.symbol()).name().replace("\"", "\"\"");
			sb.append(ranking.rank()).append(',').append(ranking.symbol()).append(",\"").append(name).append("\",")
				.append(ranking.tradingAmount()).append(',').append(duration).append(',').append(excludeCaution).append(',')
				.append(rankedAt).append('\n');
		}
		try {
			Files.createDirectories(outDir.toAbsolutePath());
			Files.writeString(outDir.resolve("_universe.csv"), sb.toString());
		} catch (IOException e) {
			log.warn("[종목군 수집] 종목 목록 파일을 쓰지 못했습니다(캔들 수집은 계속합니다): {}", e.getMessage());
		}
	}

	private boolean isOrdinaryStock(StockInfo info) {
		return info != null
			&& "STOCK".equals(info.securityType())
			&& info.isCommonShare()
			&& "ACTIVE".equals(info.status());
	}

	private Optional<Integer> parseCount(String[] args) {
		return Arrays.stream(args)
			.filter(a -> !a.startsWith("--"))
			.findFirst()
			.map(Integer::parseInt);
	}

	// "--이름=값" 형태의 옵션 값. 스프링이 넘기는 --spring.profiles.active=... 같은 인자와는 접두어가 달라 섞이지 않는다
	static Optional<String> parseOption(String[] args, String prefix) {
		return Arrays.stream(args)
			.filter(a -> a.startsWith(prefix))
			.map(a -> a.substring(prefix.length()))
			.findFirst();
	}

	// true나 false만 받는다. 오타(예: flase)가 조용히 false로 읽히면 순위 옵션이 엉뚱하게 바뀌므로 예외로 알린다
	static Optional<Boolean> parseBooleanOption(String[] args, String prefix) {
		return parseOption(args, prefix).map(value -> {
			if (!value.equals("true") && !value.equals("false")) {
				throw new IllegalArgumentException(prefix + " 값은 true 또는 false여야 합니다: " + value);
			}
			return Boolean.parseBoolean(value);
		});
	}
}
