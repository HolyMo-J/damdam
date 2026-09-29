package com.damdam.bot.backtest;

import com.damdam.bot.stocks.InvestorTradingHistory;
import com.damdam.bot.stocks.InvestorTradingHistory.StopReason;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.InvestorTradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// export-investor 프로필로 실행할 때만 동작하는 조회 전용 수집 도구. 전략 A(기관 순매수 추종)의 과거 신호 빈도를 세려고, 종목군 폴더의
// _universe.csv에 있는 종목마다 투자자별 매매동향(기관 순매수 거래량)을 과거로 끝까지 받아 <종목코드>_investor.csv로 저장한다
// (2단계 백테스트용 캔들 수집과 같은 방식). 주문 경로는 없다.
// 사용법: .\gradlew.bat bootRun --args="--spring.profiles.active=export-investor [--universe-dir=../analysis/data/universe100]"
// 종목마다 결과(기록 수, 날짜 범위, 멈춘 이유)를 _investor_summary.csv에 남긴다. 멈춘 이유가 API_END가 아니면 그 종목의 과거가 끝까지 받아진 것이 아니다
@Component
@Profile("export-investor")
public class InvestorFlowExportRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(InvestorFlowExportRunner.class);
	private static final String DEFAULT_DIR = "../analysis/data/universe100";
	// 명세상 count 최대값
	private static final int PAGE_SIZE = 100;
	// 100건 x 60페이지 = 6,000건이고 일별 기록으로 약 24년이다. 보관은 2019-04-01부터라 상한에 걸리지 않을 것으로 보지만, 걸리면 요약에 PAGE_CAP으로 남는다
	private static final int MAX_PAGES = 60;
	// STOCK_TRADING_TREND 그룹은 초당 10회라 페이지와 종목 사이에 120ms를 둔다 (429는 서비스가 Retry-After 뒤 1회 재시도한다)
	private static final long PAUSE_MS = 120;

	private final InvestorTradingService service;

	public InvestorFlowExportRunner(InvestorTradingService service) {
		this.service = service;
	}

	@Override
	public void run(String... args) throws InterruptedException, IOException {
		Path dir = Path.of(UniverseExportRunner.parseOption(args, "--universe-dir=").orElse(DEFAULT_DIR));
		List<String> symbols = parseSymbols(Files.readAllLines(dir.resolve("_universe.csv")));
		if (symbols.isEmpty()) {
			throw new IllegalStateException(dir.resolve("_universe.csv") + "에 종목이 없습니다");
		}
		log.info("[매매동향 수집] {}종목을 {}에 저장합니다.", symbols.size(), dir);

		List<String> summary = new ArrayList<>();
		summary.add("symbol,pages,records,oldest,newest,stop_reason,error");
		List<String> notComplete = new ArrayList<>();
		for (String symbol : symbols) {
			try {
				InvestorTradingHistory.Result result = InvestorTradingHistory.walk(service, symbol, PAGE_SIZE, MAX_PAGES, PAUSE_MS);
				Files.writeString(dir.resolve(symbol + "_investor.csv"), toCsv(result));
				summary.add(summaryRow(symbol, result));
				log.info("[매매동향 수집] {} {}건, 페이지 {}회, 멈춘 이유 {}", symbol, result.byDate().size(), result.pages(), result.stopReason());
				if (result.stopReason() != StopReason.API_END) {
					notComplete.add(symbol + "(" + result.stopReason() + ")");
				}
			} catch (RuntimeException e) {
				// 한 종목이 실패해도 나머지는 계속 받는다. 실패는 요약에 남기고 마지막에 다시 알린다
				summary.add(symbol + ",,,,,FAILED," + e.getClass().getSimpleName());
				notComplete.add(symbol + "(FAILED " + e.getClass().getSimpleName() + ")");
				log.warn("[매매동향 수집] {} 실패: {} {}", symbol, e.getClass().getSimpleName(), e.getMessage());
			}
			Thread.sleep(PAUSE_MS);
		}
		Files.write(dir.resolve("_investor_summary.csv"), summary);

		if (notComplete.isEmpty()) {
			log.info("[매매동향 수집] 전체 {}종목을 끝까지 받았습니다.", symbols.size());
		} else {
			log.warn("[매매동향 수집] 끝까지 받지 못한 종목 {}개: {}", notComplete.size(), notComplete);
		}
	}

	// _universe.csv(rank,symbol,name,...)의 두 번째 열. 종목명은 따옴표로 감싸져 쉼표가 들어갈 수 있지만 종목코드 열은 그 앞이라 세 번째 쉼표까지만 나눈다
	static List<String> parseSymbols(List<String> lines) {
		List<String> symbols = new ArrayList<>();
		for (String line : lines.stream().skip(1).toList()) {
			if (line.isBlank()) {
				continue;
			}
			String[] columns = line.split(",", 3);
			if (columns.length < 3 || columns[1].isBlank()) {
				throw new IllegalArgumentException("종목 목록 줄을 읽을 수 없습니다: " + line);
			}
			symbols.add(columns[1].trim());
		}
		return symbols;
	}

	// 날짜 오름차순. updated_at은 없으면 빈 칸이다
	static String toCsv(InvestorTradingHistory.Result result) {
		StringBuilder sb = new StringBuilder("date,institution_net_buy_volume,updated_at\n");
		for (InvestorTradingRecord record : result.byDate().values()) {
			sb.append(record.date()).append(',').append(record.institutionNetBuyVolume().toPlainString()).append(',')
				.append(record.updatedAt() == null ? "" : record.updatedAt().toString()).append('\n');
		}
		return sb.toString();
	}

	private static String summaryRow(String symbol, InvestorTradingHistory.Result result) {
		boolean empty = result.byDate().isEmpty();
		return symbol + "," + result.pages() + "," + result.byDate().size() + ","
			+ (empty ? "" : result.byDate().firstKey()) + "," + (empty ? "" : result.byDate().lastKey()) + "," + result.stopReason() + ",";
	}
}
