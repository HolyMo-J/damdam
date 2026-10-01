package com.damdam.bot.papertrading;

import com.damdam.bot.market.MarketCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

// signal-scan 프로필로 실행할 때만 동작하는 조회 전용 신호 판정 도구. 거래대금 상위 100종목에서 대상 종목군을 만들고(TargetUniverseService),
// 전략 A, B 신호를 한 번 판정해서(SignalScanService) 신호가 난 종목과 판정 불가 건수를 보여준다. 주문은 하지 않는다.
// 구현 (3) 러너(저녁 일괄 가상매매)가 생기기 전에 "오늘 신호가 난 종목"을 보려는 용도이고, 가상 체결이나 상태 저장은 하지 않는다.
// 신호는 "다음 거래일 시가 매수" 후보다 (docs/strategy.md "실행 방식"). 두 전략은 아직 검증 전이라 매수 추천이 아니다.
// 신호일 기본값: 오늘이 영업일이고 20:30 이후면 오늘, 아니면 직전 영업일. 20:30은 순위와 기관 매매동향이 저녁에 확정되는 시각을
// 하루 관찰(순위 20:17, 매매동향 20:21)한 값에 여유를 둔 보수적 기준이다 (docs/todo.md "확인 필요" 조회 러너 세 번째 실측).
// 결과는 콘솔 로그와 ../records/probe/signal-scan.log(git 제외 폴더)에 같은 내용으로 남고 실행할 때마다 이어 붙인다.
// 계좌 식별값이나 토큰은 다루지 않는다.
// 사용법: ./gradlew bootRun --args='--spring.profiles.active=signal-scan [--signal-date=2026-10-01]'
@Component
@Profile("signal-scan")
public class SignalScanRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(SignalScanRunner.class);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Path REPORT = Path.of("..", "records", "probe", "signal-scan.log");
	static final LocalTime CONFIRMED_AFTER = LocalTime.of(20, 30);
	private static final int MAX_LOOKBACK_DAYS = 15;
	private static final String SIGNAL_DATE_ARG = "--signal-date=";

	private final TargetUniverseService universeService;
	private final SignalScanService scanService;
	private final MarketCalendarService calendarService;
	private final Path report;
	private final Clock clock;

	@Autowired
	public SignalScanRunner(TargetUniverseService universeService, SignalScanService scanService,
							MarketCalendarService calendarService) {
		this(universeService, scanService, calendarService, REPORT, Clock.system(KST));
	}

	// 테스트에서 실제 저장소 폴더를 건드리지 않도록 결과 파일과 시계를 바꿔 끼울 수 있게 둔다
	SignalScanRunner(TargetUniverseService universeService, SignalScanService scanService,
					 MarketCalendarService calendarService, Path report, Clock clock) {
		this.universeService = universeService;
		this.scanService = scanService;
		this.calendarService = calendarService;
		this.report = report;
		this.clock = clock;
	}

	@Override
	public void run(String... args) {
		ZonedDateTime now = ZonedDateTime.now(clock);
		List<String> lines = new ArrayList<>();
		LocalDate signalDate;
		try {
			signalDate = signalDateArg(args).orElseGet(() -> defaultSignalDate(now, calendarService::isTradingDay));
		} catch (RuntimeException e) {
			lines.add("=== 신호 판정 시작 " + now.format(KST_TIME) + " (KST) ===");
			lines.add("[실패] 신호일을 정하지 못했습니다: " + e.getMessage());
			finish(lines);
			throw e;
		}
		lines.add("=== 신호 판정 시작 " + now.format(KST_TIME) + " (KST), 신호일 " + signalDate + " ===");
		try {
			TargetUniverse universe = universeService.build();
			SignalScan scan = scanService.scan(universe, signalDate);
			lines.addAll(describe(universe, scan, now));
		} catch (RuntimeException e) {
			lines.add("[실패] " + e.getMessage());
			finish(lines);
			throw e;
		}
		finish(lines);
	}

	private void finish(List<String> lines) {
		lines.add("=== 판정 끝 ===");
		lines.forEach(line -> log.info("[신호] {}", line));
		writeReport(lines);
	}

	static Optional<LocalDate> signalDateArg(String... args) {
		for (String arg : args) {
			if (arg.startsWith(SIGNAL_DATE_ARG)) {
				String value = arg.substring(SIGNAL_DATE_ARG.length());
				try {
					return Optional.of(LocalDate.parse(value));
				} catch (DateTimeParseException e) {
					throw new IllegalArgumentException("--signal-date는 2026-10-01 같은 형식이어야 합니다: " + value);
				}
			}
		}
		return Optional.empty();
	}

	// 오늘이 영업일이고 확정 시각(20:30) 이후면 오늘, 아니면 직전 영업일. 영업일 판정은 호출하는 쪽이 넘긴다
	static LocalDate defaultSignalDate(ZonedDateTime now, Predicate<LocalDate> isTradingDay) {
		LocalDate date = now.toLocalDate();
		boolean todayConfirmed = !now.toLocalTime().isBefore(CONFIRMED_AFTER);
		if (todayConfirmed && isTradingDay.test(date)) {
			return date;
		}
		for (int i = 0; i < MAX_LOOKBACK_DAYS; i++) {
			date = date.minusDays(1);
			if (isTradingDay.test(date)) {
				return date;
			}
		}
		throw new IllegalStateException("최근 " + MAX_LOOKBACK_DAYS + "일 안에서 영업일을 찾지 못했습니다.");
	}

	// 순수 함수라 테스트에서 직접 부른다. 신호가 난 종목만 근거와 함께 보여주고, 나머지는 건수로만 요약한다
	static List<String> describe(TargetUniverse universe, SignalScan scan, ZonedDateTime now) {
		List<String> lines = new ArrayList<>();
		lines.add("[대상 종목군] " + universe.members().size() + "개 (제외 " + universe.exclusions().size() + "개), 판정 시각 "
			+ KST_TIME.format(scan.scannedAt().atZone(KST)) + " (KST)");
		if (scan.signalDate().equals(now.toLocalDate()) && now.toLocalTime().isBefore(CONFIRMED_AFTER)) {
			lines.add("[주의] 신호일이 오늘인데 아직 " + CONFIRMED_AFTER + " 전이라 순위와 매매동향이 확정 전의 잠정치일 수 있습니다");
		}

		List<SignalScan.Row> ichimoku = scan.rows().stream().filter(SignalScan.Row::ichimokuSignaled).toList();
		List<SignalScan.Row> institution = scan.rows().stream().filter(SignalScan.Row::institutionSignaled).toList();

		lines.add("[전략 B: 일목 구름 상향 돌파 + 거래량 2배] 신호 " + ichimoku.size() + "건 (" + summarizeB(scan) + ")");
		ichimoku.forEach(row -> lines.add("  " + label(row) + " " + describeIchimoku(row.ichimoku().result())));
		lines.add("[전략 A: 기관 3일 연속 순매수 + 거래량 3% 이상] 신호 " + institution.size() + "건 (" + summarizeA(scan) + ")");
		institution.forEach(row -> lines.add("  " + label(row) + " " + describeInstitution(row)));

		List<String> both = ichimoku.stream().filter(SignalScan.Row::institutionSignaled).map(SignalScan.Row::symbol).toList();
		if (!both.isEmpty()) {
			lines.add("[두 전략 모두 신호] " + both);
		}
		lines.add("[참고] 신호는 다음 거래일 시가에 사는 후보입니다 (가상 체결 가정: 시가 + 슬리피지 0.2%). 두 전략은 검증 전이라 매수 추천이 아닙니다 "
			+ "(2단계 백테스트는 게이트 실패로 보류, 3단계 가상매매는 시작 전). 순위는 거래대금 순위입니다");
		return lines;
	}

	private static String label(SignalScan.Row row) {
		return "순위 " + row.rank() + " " + row.name() + "(" + row.symbol() + ")";
	}

	private static String describeIchimoku(IchimokuCloudBreakout.Result r) {
		String multiple = r.averageVolume().signum() > 0
			? r.volumeToday().divide(r.averageVolume(), 2, RoundingMode.HALF_UP).toPlainString() + "배" : "평균 0";
		return "종가 " + plain(r.closeToday()) + " > 구름 상단 " + plain(r.cloudTopToday()) + " (전날 종가 " + plain(r.closePrevious())
			+ " <= 구름 상단 " + plain(r.cloudTopPrevious()) + "), 거래량 " + plain(r.volumeToday()) + " = 직전 20일 평균 "
			+ plain(r.averageVolume()) + "의 " + multiple;
	}

	private static String describeInstitution(SignalScan.Row row) {
		InstitutionNetBuySignal.Result r = row.institution().result();
		String updated = row.institutionRecordUpdatedAt() == null ? "기록 갱신 시각 없음"
			: "기록 갱신 " + KST_TIME.format(row.institutionRecordUpdatedAt().atZone(KST));
		return "3일 기관 순매수 합 " + plain(r.netBuySum()) + " / 같은 기간 거래량 합 " + plain(r.volumeSum()) + " = "
			+ plain(r.ratioPercent()) + "% (" + updated + ")";
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	private static String summarizeB(SignalScan scan) {
		return "판정 %d, 봉 부족 %d, 데이터 오류 %d, 조회 실패 %d".formatted(
			scan.ichimokuCount(StrategyOutcome.Status.EVALUATED), scan.ichimokuCount(StrategyOutcome.Status.INSUFFICIENT_CANDLES),
			scan.ichimokuCount(StrategyOutcome.Status.DATA_ERROR), scan.ichimokuCount(StrategyOutcome.Status.FETCH_FAILED));
	}

	private static String summarizeA(SignalScan scan) {
		return "판정 %d, 봉 부족 %d, 데이터 오류 %d, 조회 실패 %d".formatted(
			scan.institutionCount(StrategyOutcome.Status.EVALUATED), scan.institutionCount(StrategyOutcome.Status.INSUFFICIENT_CANDLES),
			scan.institutionCount(StrategyOutcome.Status.DATA_ERROR), scan.institutionCount(StrategyOutcome.Status.FETCH_FAILED));
	}

	private void writeReport(List<String> lines) {
		try {
			Path parent = report.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.write(report, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("[신호] 결과 파일을 쓰지 못했습니다 ({}): {}", report, e.getMessage());
		}
	}
}
