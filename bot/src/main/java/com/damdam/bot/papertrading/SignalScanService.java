package com.damdam.bot.papertrading;

import com.damdam.bot.market.AtrService;
import com.damdam.bot.market.AverageTrueRange;
import com.damdam.bot.market.Candle;
import com.damdam.bot.stocks.InvestorTradingRecord;
import com.damdam.bot.stocks.SignalInputService;
import com.damdam.bot.stocks.StockLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// 대상 종목군의 종목마다 일봉과 기관 매매동향을 조회해 전략 A, B를 판정한다. 조회 전용이다.
// 언제 부를지(저녁, 신호 확정 뒤)와 결과를 어떻게 기록하고 알릴지는 스케줄 조각이 정한다.
// 신호 함수들의 호출 규약(docs/strategy.md "구현 현황")을 여기서 지킨다: 봉 부족(InsufficientCandlesException)만 건너뛰고,
// 데이터 오류(IllegalArgumentException 등)는 삼키지 않고 DATA_ERROR로 결과와 로그에 남긴다.
// 이 클래스는 상태가 없어서 동시에 두 번 부르는 것을 막지 않는다. 겹쳐 실행되지 않게 하는 것은 호출부(스케줄) 책임이다
@Service
public class SignalScanService {

	private static final Logger log = LoggerFactory.getLogger(SignalScanService.class);

	// 종목당 일봉 1회(MARKET_DATA_CHART 초당 20회)와 매매동향 1회(STOCK_TRADING_TREND 초당 10회)를 부르므로,
	// 종목 사이 120ms(초당 약 8회)면 두 그룹 모두 한도 안이다. 100종목이면 약 12초
	private static final long PAUSE_BETWEEN_SYMBOLS_MS = 120;

	// 전략마다 판정을 끝낸(EVALUATED) 종목이 전체의 이 비율(%) 이상이어야 결과를 정상으로 돌려준다. 미달이면 조회 장애나 입력 문제가
	// 그 전략을 사실상 눈멀게 한 것이라 "신호 0건"과 구분이 안 되므로 예외를 던진다. 50이라는 값은 데이터로 정한 것이 아니라
	// 신규 상장 등으로 봉이 모자란 종목이 절반을 넘지는 않는다는 가정이다. 가상매매를 돌려 실제 비율을 보고 조정한다
	static final int MIN_EVALUATED_PERCENT = 50;

	private final SignalInputService inputService;
	private final long pauseMs;
	private final Clock clock;

	@Autowired
	public SignalScanService(SignalInputService inputService) {
		this(inputService, PAUSE_BETWEEN_SYMBOLS_MS, Clock.system(ZoneId.of("Asia/Seoul")));
	}

	SignalScanService(SignalInputService inputService, long pauseMs, Clock clock) {
		this.inputService = inputService;
		this.pauseMs = pauseMs;
		this.clock = clock;
	}

	// signalDate는 판정하려는 거래일이다. 그 날짜 이후의 봉(다음 거래일 장 시작 뒤에 생긴 잠정 봉 등)은 버리고 판정한다.
	// signalDate 봉이 확정치인지는 이 코드가 모르므로 호출부 책임이다. scannedAt과 기록 갱신 시각을 결과에 남겨 나중에 확인할 수 있다.
	// 전략 하나라도 판정을 끝낸 종목이 MIN_EVALUATED_PERCENT 미만이면 예외를 던지고, 인터럽트를 받으면 결과를 만들지 않고 중단한다
	public SignalScan scan(TargetUniverse universe, LocalDate signalDate) {
		if (universe.members().isEmpty()) {
			throw new IllegalStateException("대상 종목군이 비어 있어 신호를 판정할 수 없습니다.");
		}

		List<SignalScan.Row> rows = new ArrayList<>();
		boolean first = true;
		for (TargetUniverse.Member member : universe.members()) {
			if (Thread.currentThread().isInterrupted()) {
				// 종료나 정지 신호를 받았는데 남은 종목을 계속 부르지 않는다. 일부만 판정한 결과는 정상 결과처럼 쓰이면 안 되므로 만들지 않는다
				throw new IllegalStateException("스캔이 중단 신호를 받아 결과를 만들지 않습니다 (%d/%d종목 처리).".formatted(rows.size(), universe.members().size()));
			}
			if (!first) {
				pause();
			}
			first = false;
			rows.add(scanOne(member, signalDate));
		}

		SignalScan scan = new SignalScan(signalDate, clock.instant(), List.copyOf(rows));
		requireCoverage(scan);

		long signaledB = rows.stream().filter(SignalScan.Row::ichimokuSignaled).count();
		long signaledA = rows.stream().filter(SignalScan.Row::institutionSignaled).count();
		log.info("[신호 판정] {} 종목 {}개: 전략 B 신호 {}건({}), 전략 A 신호 {}건({})", signalDate, rows.size(),
			signaledB, summarizeB(scan), signaledA, summarizeA(scan));
		return scan;
	}

	private void requireCoverage(SignalScan scan) {
		int total = scan.rows().size();
		long evaluatedB = scan.ichimokuCount(StrategyOutcome.Status.EVALUATED);
		long evaluatedA = scan.institutionCount(StrategyOutcome.Status.EVALUATED);
		// 나눗셈 없이 정수 곱셈으로 비교해 경계(정확히 50%)를 반올림 없이 판정한다
		boolean lowB = evaluatedB * 100 < (long) total * MIN_EVALUATED_PERCENT;
		boolean lowA = evaluatedA * 100 < (long) total * MIN_EVALUATED_PERCENT;
		if (lowB || lowA) {
			throw new IllegalStateException("전략별 판정 종목이 기준(%d%%) 미만입니다: 전략 B %s, 전략 A %s (종목 %d개)."
				.formatted(MIN_EVALUATED_PERCENT, summarizeB(scan), summarizeA(scan), total));
		}
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

	private SignalScan.Row scanOne(TargetUniverse.Member member, LocalDate signalDate) {
		String symbol = member.symbol();

		List<Candle> candles;
		try {
			candles = inputService.getDailyCandles(symbol);
		} catch (StockLookupException e) {
			log.warn("[신호 판정] {} 일봉 조회 실패로 두 전략 모두 판정하지 못했습니다: {}", symbol, e.getMessage());
			return new SignalScan.Row(member.rank(), symbol, member.name(),
				StrategyOutcome.fetchFailed(e.getMessage()), StrategyOutcome.fetchFailed(e.getMessage()), null, null);
		}
		SignalScan.EntryBasis entryBasis = entryBasisOf(candles, signalDate);

		StrategyOutcome<IchimokuCloudBreakout.Result> ichimoku =
			evaluateSafely(symbol, "전략 B", () -> IchimokuCloudBreakout.evaluate(asOf(candles, signalDate), signalDate));

		StrategyOutcome<InstitutionNetBuySignal.Result> institution;
		Instant flowUpdatedAt = null;
		try {
			List<InvestorTradingRecord> records = inputService.getInstitutionFlows(symbol);
			flowUpdatedAt = records.stream()
				.filter(r -> r.date().equals(signalDate))
				.map(InvestorTradingRecord::updatedAt)
				.filter(u -> u != null)
				.findFirst()
				.orElse(null);
			List<InstitutionNetBuySignal.DailyFlow> flows = records.stream().map(SignalScanService::toDailyFlow).toList();
			institution = evaluateSafely(symbol, "전략 A", () -> InstitutionNetBuySignal.evaluate(asOf(candles, signalDate), flows, signalDate));
		} catch (StockLookupException e) {
			log.warn("[신호 판정] {} 매매동향 조회 실패로 전략 A를 판정하지 못했습니다: {}", symbol, e.getMessage());
			institution = StrategyOutcome.fetchFailed(e.getMessage());
		}
		return new SignalScan.Row(member.rank(), symbol, member.name(), ichimoku, institution, flowUpdatedAt, entryBasis);
	}

	// 신호일 ATR(14)과 종가. 신호 판정과 같은 방식으로 신호일 이후 봉을 버린 일봉에서 뽑아 두 값이 신호 근거와 같은 봉을 보게 한다.
	// 계산하지 못하면 null이다 (봉이 15개 미만, 신호일 봉 없음, 값 형식 오류). 신호 판정 자체의 실패는 evaluateSafely가 따로 남긴다
	private static SignalScan.EntryBasis entryBasisOf(List<Candle> candles, LocalDate signalDate) {
		try {
			List<Candle> trimmed = asOf(candles, signalDate);
			if (!DailyCandles.dateOf(trimmed.get(0)).equals(signalDate)) {
				return null;
			}
			return new SignalScan.EntryBasis(AverageTrueRange.calculate(trimmed, AtrService.ATR_PERIOD),
				new BigDecimal(trimmed.get(0).closePrice()));
		} catch (IllegalArgumentException | DateTimeException e) {
			return null;
		}
	}

	// 목록 맨 앞의 신호일 이후 봉만 버린다. 신호 함수는 0번 봉이 정확히 신호일이어야 하는데, 다음 거래일 장 시작 뒤에 다시 돌리면
	// 그날의 잠정 봉이 0번에 섞여 모든 종목이 날짜 불일치로 실패하기 때문이다. 앞에서부터 신호일 이하가 나올 때까지만 날짜를 읽으므로
	// 오래된 봉의 형식 오류는 여기서 걸리지 않고 각 신호 함수의 검증이 맡는다. 신호일 봉 자체가 없으면 신호 함수의 날짜 검증이 그대로 잡는다
	private static List<Candle> asOf(List<Candle> candlesNewestFirst, LocalDate signalDate) {
		List<Candle> trimmed = candlesNewestFirst.stream()
			.dropWhile(c -> DailyCandles.dateOf(c).isAfter(signalDate))
			.toList();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("신호일(%s) 이전(포함)의 봉이 하나도 없습니다.".formatted(signalDate));
		}
		return trimmed;
	}

	private static InstitutionNetBuySignal.DailyFlow toDailyFlow(InvestorTradingRecord record) {
		return new InstitutionNetBuySignal.DailyFlow(record.date(), record.institutionNetBuyVolume());
	}

	// 봉 부족은 정상적으로 건너뛰고, 그 밖의 입력 오류(날짜 불일치, 순서 오류, 기록 누락, 숫자나 날짜 형식 오류)는 DATA_ERROR로 남긴다
	private <T> StrategyOutcome<T> evaluateSafely(String symbol, String strategy, Supplier<T> evaluation) {
		try {
			return StrategyOutcome.evaluated(evaluation.get());
		} catch (InsufficientCandlesException e) {
			return StrategyOutcome.insufficientCandles(e.getMessage());
		} catch (IllegalArgumentException | DateTimeException e) {
			log.warn("[신호 판정] {} {} 입력 데이터 오류로 판정하지 않았습니다: {}", symbol, strategy, e.getMessage());
			return StrategyOutcome.dataError(e.getMessage());
		}
	}

	private void pause() {
		if (pauseMs <= 0) {
			return;
		}
		try {
			Thread.sleep(pauseMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
