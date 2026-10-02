package com.damdam.bot.papertrading;

import com.damdam.bot.stocks.InvestorTradingHistory;
import com.damdam.bot.stocks.InvestorTradingHistory.StopReason;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.InvestorTradingService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

// investor-history-probe 프로필로 실행할 때만 동작하는 조회 전용 도구. 전략 A가 쓰는 투자자별 매매동향이 과거 얼마나 오래 보관되는지
// (일봉 백테스트를 병행할 수 있는지, docs/measurements.md "매매동향 보관 기간" 참고)를 종목마다 until과 nextUntil로 과거 방향으로 끝까지 넘기며 잰다.
// 페이지 상한(MAX_PAGES)에 걸려 멈춘 것과 API가 더 없다고 응답한 것을 구분해서 출력한다: 상한에 걸린 날짜는 보관 한계가 아니라 우리 한도다
// (수집기 상한에 잘린 1988년을 데이터 특성으로 착각했던 사고와 같은 실수를 막으려는 것). 주문 경로는 없다.
// 사용법: .\gradlew.bat bootRun --args="--spring.profiles.active=investor-history-probe [종목코드 ...]" (기본 삼성전자와 코스닥 종목 하나)
@Component
@Profile("investor-history-probe")
public class InvestorHistoryProbeRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(InvestorHistoryProbeRunner.class);

	// 명세상 count 최대값
	static final int PAGE_SIZE = 100;
	// 100건 x 60페이지 = 6,000건이고 일별 기록으로 약 24년이다. 여기서 멈추면 보관 한계가 아니다
	static final int MAX_PAGES = 60;
	// STOCK_TRADING_TREND 그룹은 초당 10회라 페이지 사이에 120ms를 둔다 (429는 서비스가 Retry-After 뒤 1회 재시도한다)
	private static final long PAUSE_MS = 120;
	private static final List<String> DEFAULT_SYMBOLS = List.of("005930", "196170");
	private static final Path REPORT = Path.of("..", "records", "probe", "investor-history.log");

	record Summary(String symbol, int pages, int recordCount, LocalDate oldest, LocalDate newest, StopReason stopReason,
			long largestGapDays, LocalDate gapFrom, LocalDate gapTo, SortedMap<Integer, int[]> perYear) {
	}

	private final InvestorTradingService service;
	private final long pauseMs;
	private final Path report;

	@Autowired
	public InvestorHistoryProbeRunner(InvestorTradingService service) {
		this(service, PAUSE_MS, REPORT);
	}

	InvestorHistoryProbeRunner(InvestorTradingService service, long pauseMs, Path report) {
		this.service = service;
		this.pauseMs = pauseMs;
		this.report = report;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		List<String> symbols = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
		if (symbols.isEmpty()) {
			symbols = DEFAULT_SYMBOLS;
		}
		List<String> lines = new ArrayList<>();
		lines.add("=== 매매동향 보관 기간 조사 " + Instant.now() + " ===");
		for (String symbol : symbols) {
			try {
				lines.addAll(describe(probe(symbol)));
			} catch (RuntimeException e) {
				lines.add("[매매동향 보관 기간] " + symbol + " 조회 실패: " + e.getClass().getSimpleName() + " " + e.getMessage());
			}
		}
		lines.forEach(line -> log.info("[조사] {}", line));
		writeReport(lines);
	}

	// 종목 하나의 매매동향을 과거로 끝까지 넘기며 날짜 범위를 잰다 (넘기는 방법과 종료 판정은 InvestorTradingHistory)
	Summary probe(String symbol) throws InterruptedException {
		InvestorTradingHistory.Result result = InvestorTradingHistory.walk(service, symbol, PAGE_SIZE, MAX_PAGES, pauseMs);
		return summarize(symbol, result.pages(), result.byDate(), result.stopReason());
	}

	private static Summary summarize(String symbol, int pages, SortedMap<LocalDate, InvestorTradingRecord> byDate, StopReason reason) {
		SortedMap<Integer, int[]> perYear = new TreeMap<>();
		long largestGap = 0;
		LocalDate gapFrom = null;
		LocalDate gapTo = null;
		LocalDate previous = null;
		for (Map.Entry<LocalDate, InvestorTradingRecord> entry : byDate.entrySet()) {
			int[] counts = perYear.computeIfAbsent(entry.getKey().getYear(), year -> new int[2]);
			counts[0]++;
			if (entry.getValue().institutionNetBuyVolume().signum() == 0) {
				counts[1]++;
			}
			if (previous != null) {
				long gap = ChronoUnit.DAYS.between(previous, entry.getKey());
				if (gap > largestGap) {
					largestGap = gap;
					gapFrom = previous;
					gapTo = entry.getKey();
				}
			}
			previous = entry.getKey();
		}
		return new Summary(symbol, pages, byDate.size(), byDate.isEmpty() ? null : byDate.firstKey(),
			byDate.isEmpty() ? null : byDate.lastKey(), reason, largestGap, gapFrom, gapTo, perYear);
	}

	static List<String> describe(Summary s) {
		List<String> lines = new ArrayList<>();
		lines.add("[매매동향 보관 기간] " + s.symbol() + ": 기록 " + s.recordCount() + "건, 페이지 " + s.pages() + "회 (페이지당 최대 " + PAGE_SIZE + "건)");
		lines.add("  날짜 범위: " + s.oldest() + " ~ " + s.newest());
		lines.add("  멈춘 이유: " + s.stopReason() + " - " + s.stopReason().text);
		if (s.gapFrom() != null) {
			lines.add("  가장 큰 날짜 간격: " + s.largestGapDays() + "일 (" + s.gapFrom() + " ~ " + s.gapTo() + "). 명절 연휴는 10일 안팎이라, 이보다 훨씬 크면 그 구간의 기록이 빠진 것일 수 있다");
		}
		StringBuilder years = new StringBuilder("  연도별 건수(기관 순매수가 0인 건수): ");
		s.perYear().forEach((year, counts) -> years.append(year).append(' ').append(counts[0]).append('(').append(counts[1]).append(") "));
		lines.add(years.toString().trim());
		return lines;
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
}
