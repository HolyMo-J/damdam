package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

// 전략 B: 일목 구름대 상향 돌파와 거래량 급증 (docs/strategy.md "전략 B" 참고). 일봉만 쓰는 순수 계산이라 API나 주문 코드에 의존하지 않는다
//   조건: 전날 종가 <= 전날 구름 상단, 신호일 종가 > 신호일 구름 상단, 신호일 거래량 >= 직전 20거래일 평균의 2배 (거래량 0인 날은 평균에서 제외)
//   구름 상단(그날 위치): 선행스팬1, 2 중 큰 값. 선행스팬은 계산한 날보다 25봉 뒤에 그려지므로, 그날 위치의 값은 25거래일 전 봉까지의 데이터로 계산한 것이다 (오늘 이후 정보를 쓰지 않는다)
//   25봉인 이유: 사용자가 보는 토스증권 차트에 맞춘다. 2026-09-29 토스 차트 스크린샷(삼성전자, NH투자증권, POSCO홀딩스 일봉 세 종목)에서 구름 경계선의
//   픽셀 위치를 대조해 25칸임을 확인했다 (26칸이면 어긋남, 근거는 docs/strategy.md "전략 B"). TradingView가 설정값에서 1을 뺀 칸 수로 그린다고 알려진 것과 같은
//   결과지만, 토스가 그 이유로 그런지는 확인 못 했다. 다른 차트 프로그램과는 다를 수 있다
public final class IchimokuCloudBreakout {

	public static final int TENKAN_PERIOD = 9;
	public static final int KIJUN_PERIOD = 26;
	public static final int SENKOU_B_PERIOD = 52;
	// 선행스팬을 앞으로 그리는 칸 수. 설정값(26)이 아니라 그려지는 칸 수(25)다. 위 주석 참고
	public static final int DISPLACEMENT = 25;
	public static final int VOLUME_WINDOW = 20;
	public static final int VOLUME_MULTIPLE = 2;
	// 전날(인덱스 1)의 구름 상단이 인덱스 1 + 25 부터 52개 봉(끝 인덱스 77)을 쓰므로 78개가 필요하다
	public static final int MIN_CANDLES = 1 + DISPLACEMENT + SENKOU_B_PERIOD;

	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	private IchimokuCloudBreakout() {
	}

	// 판정 근거를 함께 담아, 가상매매 기록과 사후 검증에서 신호가 왜 났는지 다시 계산해볼 수 있게 한다
	public record Result(
		LocalDate signalDate,
		boolean crossedAboveCloud,
		boolean volumeSurge,
		BigDecimal closeToday,
		BigDecimal cloudTopToday,
		BigDecimal closePrevious,
		BigDecimal cloudTopPrevious,
		BigDecimal volumeToday,
		// 표시용(소수 4자리 반올림). 판정은 반올림 없이 정확한 곱셈 비교로 한다. 거래량이 있는 날이 하나도 없으면 0
		BigDecimal averageVolume
	) {
		public boolean signaled() {
			return crossedAboveCloud && volumeSurge;
		}
	}

	// candlesNewestFirst: 최신순(timestamp 내림차순), 0번이 확정된 신호일 봉이어야 한다 (장중의 미확정 봉을 넣으면 안 된다).
	// expectedSignalDate: 호출하는 쪽이 신호를 판정하려는 거래일. 0번 봉의 날짜와 다르면 예외를 던진다 (오래된 목록으로 며칠 전 신호를
	// 조용히 판정하거나, 신호일 봉을 걸러낸 목록을 넣는 실수를 막는다). 그 봉이 정말 확정치인지는 이 코드가 알 수 없다.
	// 수정주가 기준 일봉이어야 하고, 78개 미만이면 InsufficientCandlesException, 시간 순서가 어긋나거나(중복 날짜 포함) 날짜가 안 맞으면 IllegalArgumentException
	public static Result evaluate(List<Candle> candlesNewestFirst, LocalDate expectedSignalDate) {
		if (candlesNewestFirst.size() < MIN_CANDLES) {
			throw new InsufficientCandlesException(
				"일목 구름 계산에는 캔들이 최소 %d개 필요한데 %d개만 있습니다.".formatted(MIN_CANDLES, candlesNewestFirst.size()));
		}
		DailyCandles.requireStrictlyDescending(candlesNewestFirst);
		DailyCandles.requireLatestDate(candlesNewestFirst, expectedSignalDate);

		BigDecimal closeToday = new BigDecimal(candlesNewestFirst.get(0).closePrice());
		BigDecimal closePrevious = new BigDecimal(candlesNewestFirst.get(1).closePrice());
		BigDecimal cloudTopToday = cloudTop(candlesNewestFirst, 0);
		BigDecimal cloudTopPrevious = cloudTop(candlesNewestFirst, 1);
		boolean crossedAboveCloud = closePrevious.compareTo(cloudTopPrevious) <= 0 && closeToday.compareTo(cloudTopToday) > 0;

		BigDecimal volumeToday = new BigDecimal(candlesNewestFirst.get(0).volume());
		BigDecimal volumeSum = BigDecimal.ZERO;
		int nonZeroDays = 0;
		for (int i = 1; i <= VOLUME_WINDOW; i++) {
			BigDecimal volume = new BigDecimal(candlesNewestFirst.get(i).volume());
			if (volume.signum() > 0) {
				volumeSum = volumeSum.add(volume);
				nonZeroDays++;
			}
		}

		// 거래량 >= 평균 x 2 를 나눗셈 없이 (거래량 x 일수 >= 합계 x 2)로 비교해 반올림 오차를 없앤다
		boolean volumeSurge = nonZeroDays > 0
			&& volumeToday.multiply(BigDecimal.valueOf(nonZeroDays)).compareTo(volumeSum.multiply(BigDecimal.valueOf(VOLUME_MULTIPLE))) >= 0;
		BigDecimal averageVolume = nonZeroDays == 0
			? BigDecimal.ZERO
			: volumeSum.divide(BigDecimal.valueOf(nonZeroDays), 4, RoundingMode.HALF_UP);

		return new Result(expectedSignalDate, crossedAboveCloud, volumeSurge, closeToday, cloudTopToday, closePrevious, cloudTopPrevious, volumeToday, averageVolume);
	}

	// index 위치 날짜의 구름 상단. 그날 위치의 선행스팬은 DISPLACEMENT(25)거래일 전(base) 봉까지의 데이터로 계산한다
	static BigDecimal cloudTop(List<Candle> candlesNewestFirst, int index) {
		int base = index + DISPLACEMENT;
		BigDecimal tenkan = midpoint(candlesNewestFirst, base, TENKAN_PERIOD);
		BigDecimal kijun = midpoint(candlesNewestFirst, base, KIJUN_PERIOD);
		BigDecimal senkouA = tenkan.add(kijun).divide(TWO);
		BigDecimal senkouB = midpoint(candlesNewestFirst, base, SENKOU_B_PERIOD);
		return senkouA.max(senkouB);
	}

	// start 위치 봉부터 과거 방향으로 period개 봉의 (최고가 + 최저가) / 2. 2로 나누는 것은 항상 딱 떨어져 반올림이 없다
	private static BigDecimal midpoint(List<Candle> candlesNewestFirst, int start, int period) {
		BigDecimal highest = null;
		BigDecimal lowest = null;
		for (int i = start; i < start + period; i++) {
			Candle candle = candlesNewestFirst.get(i);
			BigDecimal high = new BigDecimal(candle.highPrice());
			BigDecimal low = new BigDecimal(candle.lowPrice());
			highest = highest == null ? high : highest.max(high);
			lowest = lowest == null ? low : lowest.min(low);
		}
		return highest.add(lowest).divide(TWO);
	}
}
