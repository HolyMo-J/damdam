package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IchimokuCloudBreakoutTest {

	// 인덱스 0이 신호일(최신). 기본값: 고가 110, 저가 90, 종가 100, 거래량 1000
	// 구름 손계산용으로 인덱스 25의 고가를 130, 인덱스 34~50의 저가를 70으로 바꿔서 시작한다 (선행스팬을 25칸 뒤에 그리므로 신호일 base = 25)
	//   신호일(base 25): 전환선 (130+90)/2=110, 기준선 (130+70)/2=100 -> 선행스팬1 105, 선행스팬2 (130+70)/2=100 -> 구름 상단 105
	//   전날(base 26):   전환선 (110+70)/2=90,  기준선 (110+70)/2=90  -> 선행스팬1 90,  선행스팬2 (110+70)/2=90  -> 구름 상단 90
	private static class Series {
		final int count;
		final String[] high;
		final String[] low;
		final String[] close;
		final String[] volume;

		Series(int count) {
			this.count = count;
			high = new String[count];
			low = new String[count];
			close = new String[count];
			volume = new String[count];
			for (int i = 0; i < count; i++) {
				high[i] = "110";
				low[i] = "90";
				close[i] = "100";
				volume[i] = "1000";
			}
			high[25] = "130";
			for (int i = 34; i <= 50; i++) {
				low[i] = "70";
			}
		}

		List<Candle> build() {
			LocalDate today = LocalDate.of(2026, 9, 28);
			List<Candle> candles = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				String timestamp = today.minusDays(i).atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toString();
				candles.add(new Candle(timestamp, close[i], high[i], low[i], close[i], volume[i], "KRW"));
			}
			return candles;
		}
	}

	private static final LocalDate SIGNAL_DAY = LocalDate.of(2026, 9, 28);

	private static Series series() {
		return new Series(IchimokuCloudBreakout.MIN_CANDLES);
	}

	private static IchimokuCloudBreakout.Result evaluate(List<Candle> candles) {
		return IchimokuCloudBreakout.evaluate(candles, SIGNAL_DAY);
	}

	// 창의 끝 인덱스(가장 과거 봉)에서만 값이 결정되도록 봉마다 값이 다른 시리즈. 기간, 표시 이동, 창 경계가 한 칸만 틀려도 결과가 달라진다.
	//   고가 = 200 + 2i, 저가 = 100 - i  ->  창의 최고가와 최저가는 항상 창 끝 인덱스 e의 봉이라 중간값 = 150 + e/2
	//   신호일(base 25): 전환선 e=33 -> 166.5, 기준선 e=50 -> 175,   선행스팬1 170.75, 선행스팬2 e=76 -> 188   -> 구름 상단 188 (스팬2가 상단)
	//   전날(base 26):   전환선 e=34 -> 167,   기준선 e=51 -> 175.5, 선행스팬1 171.25, 선행스팬2 e=77 -> 188.5 -> 구름 상단 188.5
	//   거래량 = 1000 + i (i=1..20) -> 합계 20210, 평균 1010.5 (2배 = 2021)
	private static Series ramp() {
		Series s = series();
		for (int i = 0; i < s.count; i++) {
			s.high[i] = String.valueOf(200 + 2 * i);
			s.low[i] = String.valueOf(100 - i);
			s.close[i] = "150";
			s.volume[i] = String.valueOf(1000 + i);
		}
		return s;
	}

	@Test
	void seventyEightCandlesAreEnoughAndSeventySevenAreNot() {
		// 전날(인덱스 1)의 선행스팬2가 인덱스 26부터 52개, 즉 77번까지 읽는다 -> 78개. 78개가 실제로 예외 없이 계산돼야 한다
		assertEquals(78, IchimokuCloudBreakout.MIN_CANDLES);
		evaluate(series().build());
		assertThrows(InsufficientCandlesException.class, () -> evaluate(new Series(77).build()));
	}

	@Test
	void rampCloudTopIsDecidedByEachWindowsOldestBarSoAnyOffByOneChangesIt() {
		List<Candle> candles = ramp().build();

		assertEquals(0, new BigDecimal("188").compareTo(IchimokuCloudBreakout.cloudTop(candles, 0)));
		assertEquals(0, new BigDecimal("188.5").compareTo(IchimokuCloudBreakout.cloudTop(candles, 1)));
	}

	@Test
	void rampWithSpikeMakesSenkouATheTopSoTheKijunWindowEdgeMatters() {
		// 램프에서는 선행스팬2가 상단이라 기준선 기간이 결과에 드러나지 않는다. 인덱스 30에 고가 400 스파이크를 넣어 선행스팬1이 상단이 되게 한다
		//   신호일(base 25): 전환선 (400+67)/2=233.5, 기준선 (400+50)/2=225 -> 선행스팬1 229.25, 선행스팬2 (400+24)/2=212   -> 상단 229.25
		//   전날(base 26):   전환선 (400+66)/2=233,   기준선 (400+49)/2=224.5 -> 선행스팬1 228.75, 선행스팬2 (400+23)/2=211.5 -> 상단 228.75
		Series s = ramp();
		s.high[30] = "400";
		List<Candle> candles = s.build();

		assertEquals(0, new BigDecimal("229.25").compareTo(IchimokuCloudBreakout.cloudTop(candles, 0)));
		assertEquals(0, new BigDecimal("228.75").compareTo(IchimokuCloudBreakout.cloudTop(candles, 1)));
	}

	@Test
	void rampSignalBoundariesForCloudTopAndVolume() {
		Series s = ramp();
		s.close[1] = "188.5";    // 전날 구름 상단과 같음 (이하이므로 통과)
		s.close[0] = "188.1";  // 신호일 구름 상단 188 초과
		s.volume[0] = "2021";  // 평균 1010.5의 정확히 2배
		assertTrue(evaluate(s.build()).signaled());

		s.volume[0] = "2020";  // 20일 창이 19일이면 평균 1010이라 통과했을 값, 21일 창이면 더 높아져 여기서도 탈락
		assertFalse(evaluate(s.build()).volumeSurge());

		s.volume[0] = "2021";
		s.close[0] = "188";  // 구름 상단과 같음
		assertFalse(evaluate(s.build()).crossedAboveCloud());

		s.close[0] = "188.1";
		s.close[1] = "188.6";  // 전날 구름 상단 초과
		assertFalse(evaluate(s.build()).crossedAboveCloud());
	}

	@Test
	void resultRecordsTheSignalDayAndRequiresTheLatestCandleToBeThatDay() {
		assertEquals(SIGNAL_DAY, evaluate(series().build()).signalDate());

		// 신호일 봉이 빠진 목록(하루 전이 0번)은 신호일 판정에 쓸 수 없다
		List<Candle> stale = new Series(IchimokuCloudBreakout.MIN_CANDLES + 1).build().subList(1, IchimokuCloudBreakout.MIN_CANDLES + 1);
		assertThrows(IllegalArgumentException.class, () -> evaluate(stale));
	}

	@Test
	void cloudTopUsesValuesDisplacedBy25DaysAndMatchesHandCalculation() {
		List<Candle> candles = series().build();

		assertEquals(0, new BigDecimal("105").compareTo(IchimokuCloudBreakout.cloudTop(candles, 0)));
		assertEquals(0, new BigDecimal("90").compareTo(IchimokuCloudBreakout.cloudTop(candles, 1)));
	}

	@Test
	void cloudDoesNotDependOnRecentBars() {
		// 최근 25개 봉(인덱스 0~24)의 고가와 저가를 극단값으로 바꿔도 구름은 그대로여야 한다 (룩어헤드 방지)
		Series s = series();
		for (int i = 0; i < 25; i++) {
			s.high[i] = "100000";
			s.low[i] = "1";
		}
		List<Candle> candles = s.build();

		assertEquals(0, new BigDecimal("105").compareTo(IchimokuCloudBreakout.cloudTop(candles, 0)));
		assertEquals(0, new BigDecimal("90").compareTo(IchimokuCloudBreakout.cloudTop(candles, 1)));
	}

	@Test
	void signalsWhenCloseCrossesAboveCloudTopWithVolumeSurge() {
		Series s = series();
		s.close[1] = "90";   // 전날 구름 상단 90 이하
		s.close[0] = "106";  // 신호일 구름 상단 105 초과
		s.volume[0] = "2000"; // 직전 20일 평균 1000의 정확히 2배

		IchimokuCloudBreakout.Result result = evaluate(s.build());

		assertTrue(result.crossedAboveCloud());
		assertTrue(result.volumeSurge());
		assertTrue(result.signaled());
		assertEquals(0, new BigDecimal("105").compareTo(result.cloudTopToday()));
		assertEquals(0, new BigDecimal("90").compareTo(result.cloudTopPrevious()));
		assertEquals(0, new BigDecimal("1000").compareTo(result.averageVolume()));
	}

	@Test
	void closeEqualToCloudTopIsNotABreakout() {
		Series s = series();
		s.close[1] = "90";
		s.close[0] = "105"; // 구름 상단과 같음, "위로 마감"이 아니다
		s.volume[0] = "5000";

		assertFalse(evaluate(s.build()).crossedAboveCloud());
	}

	@Test
	void previousCloseAboveCloudTopIsNotABreakout() {
		Series s = series();
		s.close[1] = "91"; // 전날 구름 상단 90 초과, 이미 위에 있었다
		s.close[0] = "106";
		s.volume[0] = "5000";

		assertFalse(evaluate(s.build()).crossedAboveCloud());
	}

	@Test
	void volumeJustBelowTwiceAverageIsNotASurge() {
		Series s = series();
		s.close[1] = "90";
		s.close[0] = "106";
		s.volume[0] = "1999";

		IchimokuCloudBreakout.Result result = evaluate(s.build());

		assertTrue(result.crossedAboveCloud());
		assertFalse(result.volumeSurge());
		assertFalse(result.signaled());
	}

	@Test
	void zeroVolumeDaysAreExcludedFromTheAverage() {
		Series s = series();
		s.close[1] = "90";
		s.close[0] = "106";
		for (int i = 1; i <= 10; i++) {
			s.volume[i] = "0"; // 거래정지 등으로 거래량 0인 날
		}
		s.volume[0] = "1500"; // 0을 뺀 평균 1000의 2배 미만, 0을 포함한 평균 500이었다면 2배 이상이라 통과했을 값

		IchimokuCloudBreakout.Result result = evaluate(s.build());

		assertFalse(result.volumeSurge());
		assertEquals(0, new BigDecimal("1000").compareTo(result.averageVolume()));
	}

	@Test
	void noVolumeHistoryMeansNoSurge() {
		Series s = series();
		s.close[1] = "90";
		s.close[0] = "106";
		for (int i = 1; i <= 20; i++) {
			s.volume[i] = "0";
		}
		s.volume[0] = "5000";

		IchimokuCloudBreakout.Result result = evaluate(s.build());

		assertFalse(result.volumeSurge());
		assertEquals(0, BigDecimal.ZERO.compareTo(result.averageVolume()));
	}

	@Test
	void volumeWindowIsThe20DaysBeforeTheSignalDayExcludingIt() {
		Series s = series();
		s.close[1] = "90";
		s.close[0] = "106";
		s.volume[0] = "2000";
		s.volume[21] = "1000000"; // 20일 창 바깥, 결과에 영향이 없어야 한다

		assertTrue(evaluate(s.build()).volumeSurge());
	}

	@Test
	void tooFewCandlesIsADifferentExceptionFromDataErrors() {
		// 신규 상장처럼 정상적인 봉 부족은 전용 예외, 나머지 데이터 오류는 IllegalArgumentException. 호출하는 쪽이 앞의 것만 건너뛸 수 있어야 한다
		assertFalse(IllegalArgumentException.class.isAssignableFrom(InsufficientCandlesException.class));
		assertThrows(InsufficientCandlesException.class, () -> evaluate(new Series(IchimokuCloudBreakout.MIN_CANDLES - 1).build()));
	}

	@Test
	void rejectsOldestFirstOrder() {
		List<Candle> reversed = new ArrayList<>(series().build());
		java.util.Collections.reverse(reversed);

		assertThrows(IllegalArgumentException.class, () -> evaluate(reversed));
	}

	@Test
	void rejectsDuplicateDates() {
		List<Candle> candles = new ArrayList<>(series().build());
		candles.set(5, candles.get(4));

		assertThrows(IllegalArgumentException.class, () -> evaluate(candles));
	}
}
