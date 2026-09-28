package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 전략 A: 기관 순매수 추종, 거래량 기준 (docs/strategy.md "전략 A" 참고). API나 주문 코드에 의존하지 않는 순수 계산
//   조건: 최근 3거래일 연속 기관 순매수(netBuyVolume > 0)이고, 3거래일 합계 기관 순매수 거래량이 같은 기간 총 거래량(일봉 volume 합)의 3% 이상
//   3거래일은 일봉 날짜로 정한다 (신호일 포함 최근 3개 봉). 기관 매매동향은 그 날짜와 맞는 기록을 찾아 쓴다
public final class InstitutionNetBuySignal {

	public static final int CONSECUTIVE_DAYS = 3;
	public static final int MIN_RATIO_PERCENT = 3;

	private InstitutionNetBuySignal() {
	}

	// 투자자별 매매동향 API의 records 한 건에서 날짜와 institution.netBuyVolume만 뽑은 입력 (음수 가능)
	public record DailyFlow(LocalDate date, BigDecimal institutionNetBuyVolume) {
	}

	// 판정 근거를 함께 담아, 가상매매 기록과 사후 검증에서 신호가 왜 났는지 다시 계산해볼 수 있게 한다
	public record Result(
		LocalDate signalDate,
		boolean consecutiveNetBuy,
		boolean ratioMet,
		BigDecimal netBuySum,
		BigDecimal volumeSum,
		// 표시용(소수 4자리 반올림). 판정은 반올림 없이 정확한 곱셈 비교로 한다. 거래량 합계가 0이면 0
		BigDecimal ratioPercent
	) {
		public boolean signaled() {
			return consecutiveNetBuy && ratioMet;
		}
	}

	// candlesNewestFirst: 최신순(timestamp 내림차순) 일봉, 0번이 확정된 신호일. 최근 3개 봉만 쓰고 나머지는 무시한다.
	// expectedSignalDate: 호출하는 쪽이 신호를 판정하려는 거래일. 0번 봉의 날짜와 다르면 예외를 던진다.
	// flows: 순서 무관. 신호일 이후 날짜(잠정치 등)나 3거래일보다 오래된 기록은 무시하지만, 3거래일 중 하루라도 기록이 없거나
	// 같은 날짜가 두 번 있으면 예외를 던진다 (없는 날을 0으로 채우면 조용히 틀린 신호가 되기 때문). 일봉이 3개 미만이면 InsufficientCandlesException
	public static Result evaluate(List<Candle> candlesNewestFirst, List<DailyFlow> flows, LocalDate expectedSignalDate) {
		if (candlesNewestFirst.size() < CONSECUTIVE_DAYS) {
			throw new InsufficientCandlesException(
				"기관 순매수 신호에는 일봉이 최소 %d개 필요한데 %d개만 있습니다.".formatted(CONSECUTIVE_DAYS, candlesNewestFirst.size()));
		}
		List<Candle> recent = candlesNewestFirst.subList(0, CONSECUTIVE_DAYS);
		DailyCandles.requireStrictlyDescending(recent);
		DailyCandles.requireLatestDate(recent, expectedSignalDate);
		requireNoDuplicateDates(flows);

		BigDecimal netBuySum = BigDecimal.ZERO;
		BigDecimal volumeSum = BigDecimal.ZERO;
		boolean consecutiveNetBuy = true;
		for (int i = 0; i < CONSECUTIVE_DAYS; i++) {
			Candle candle = candlesNewestFirst.get(i);
			LocalDate date = DailyCandles.dateOf(candle);
			BigDecimal netBuy = flows.stream()
				.filter(f -> f.date().equals(date))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("기관 매매동향 기록이 없는 거래일이 있습니다: " + date))
				.institutionNetBuyVolume();

			if (netBuy.signum() <= 0) {
				consecutiveNetBuy = false;
			}
			netBuySum = netBuySum.add(netBuy);
			volumeSum = volumeSum.add(new BigDecimal(candle.volume()));
		}

		// 순매수 합계 >= 거래량 합계 x 3% 를 나눗셈 없이 (순매수 x 100 >= 거래량 x 3)로 비교해 반올림 오차를 없앤다
		boolean ratioMet = volumeSum.signum() > 0
			&& netBuySum.multiply(BigDecimal.valueOf(100)).compareTo(volumeSum.multiply(BigDecimal.valueOf(MIN_RATIO_PERCENT))) >= 0;
		BigDecimal ratioPercent = volumeSum.signum() == 0
			? BigDecimal.ZERO
			: netBuySum.multiply(BigDecimal.valueOf(100)).divide(volumeSum, 4, RoundingMode.HALF_UP);

		return new Result(expectedSignalDate, consecutiveNetBuy, ratioMet, netBuySum, volumeSum, ratioPercent);
	}

	private static void requireNoDuplicateDates(List<DailyFlow> flows) {
		Set<LocalDate> seen = new HashSet<>();
		for (DailyFlow flow : flows) {
			if (!seen.add(flow.date())) {
				throw new IllegalArgumentException("기관 매매동향에 같은 날짜가 중복됩니다: " + flow.date());
			}
		}
	}
}
