package com.damdam.bot.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

// export-candles 프로필로 실행할 때만 동작. 사용법: ./gradlew bootRun --args='--spring.profiles.active=export-candles 005930'
@Component
@Profile("export-candles")
public class CandleExportRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(CandleExportRunner.class);

	private final CandleHistoryExporter exporter;

	public CandleExportRunner(CandleHistoryExporter exporter) {
		this.exporter = exporter;
	}

	@Override
	public void run(String... args) {
		Optional<String> symbol = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst();
		if (symbol.isEmpty()) {
			log.error("종목 심볼을 지정하세요. 예: ./gradlew bootRun --args='--spring.profiles.active=export-candles 005930'");
			return;
		}

		Path adjustedCsv = Path.of("..", "analysis", "data", symbol.get() + "_daily.csv");
		Path unadjustedCsv = Path.of("..", "analysis", "data", symbol.get() + "_daily_unadjusted.csv");

		int adjustedCount = exporter.exportDailyHistory(symbol.get(), adjustedCsv, true);
		log.info("[캔들 수집] {} 수정주가 총 {}개 봉 수집 완료.", symbol.get(), adjustedCount);

		int unadjustedCount = exporter.exportDailyHistory(symbol.get(), unadjustedCsv, false);
		log.info("[캔들 수집] {} 원본 시세 총 {}개 봉 수집 완료.", symbol.get(), unadjustedCount);
	}
}
