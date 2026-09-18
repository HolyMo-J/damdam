package com.damdam.bot.backtest;

import com.damdam.bot.ranking.Ranking;
import com.damdam.bot.ranking.RankingService;
import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
@Component
@Profile("export-universe")
public class UniverseExportRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(UniverseExportRunner.class);
	private static final int DEFAULT_COUNT = 30;
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

		// 최근 1년 국내 거래대금 상위, 투자유의 종목 제외 (복권형 소형주 배제 목적, docs/strategy.md 참고)
		List<Ranking> rankings = rankingService.getMarketTradingAmountTop("KR", "1y", RANKING_POOL_SIZE);
		Map<String, StockInfo> infoBySymbol = stockInfoService.getStocks(rankings.stream().map(Ranking::symbol).toList())
			.stream()
			.collect(Collectors.toMap(StockInfo::symbol, Function.identity()));

		List<Ranking> stocksOnly = rankings.stream()
			.filter(r -> isOrdinaryStock(infoBySymbol.get(r.symbol())))
			.limit(count)
			.toList();

		log.info("[종목군 수집] 거래대금 상위 {}종목 중 ETF/ETN 등을 제외하고 보통주 {}종목 확보. 캔들 수집을 시작합니다.",
			rankings.size(), stocksOnly.size());

		for (Ranking ranking : stocksOnly) {
			StockInfo info = infoBySymbol.get(ranking.symbol());
			log.info("[종목군 수집] {}위 {}({}) 거래대금 {}", ranking.rank(), ranking.symbol(), info.name(), ranking.tradingAmount());
			Path outputCsv = Path.of("..", "analysis", "data", ranking.symbol() + "_daily.csv");
			exporter.exportDailyHistory(ranking.symbol(), outputCsv);
			Thread.sleep(PAUSE_BETWEEN_SYMBOLS_MS);
		}

		log.info("[종목군 수집] 전체 {}종목 수집 완료.", stocksOnly.size());
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
}
