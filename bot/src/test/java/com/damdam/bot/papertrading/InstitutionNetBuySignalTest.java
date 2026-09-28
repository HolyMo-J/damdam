package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;
import com.damdam.bot.papertrading.InstitutionNetBuySignal.DailyFlow;
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

class InstitutionNetBuySignalTest {

	private static final LocalDate SIGNAL_DAY = LocalDate.of(2026, 9, 28);

	// 최신순 일봉. volumes[0]이 신호일 거래량이고 날짜는 하루씩 과거로 간다
	private static List<Candle> candles(String... volumes) {
		List<Candle> candles = new ArrayList<>();
		for (int i = 0; i < volumes.length; i++) {
			String timestamp = SIGNAL_DAY.minusDays(i).atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toString();
			candles.add(new Candle(timestamp, "100", "110", "90", "100", volumes[i], "KRW"));
		}
		return candles;
	}

	// netBuys[0]이 신호일 기관 순매수량
	private static List<DailyFlow> flows(String... netBuys) {
		List<DailyFlow> flows = new ArrayList<>();
		for (int i = 0; i < netBuys.length; i++) {
			flows.add(new DailyFlow(SIGNAL_DAY.minusDays(i), new BigDecimal(netBuys[i])));
		}
		return flows;
	}

	private static InstitutionNetBuySignal.Result evaluate(List<Candle> candles, List<DailyFlow> flows) {
		return InstitutionNetBuySignal.evaluate(candles, flows, SIGNAL_DAY);
	}

	@Test
	void signalsWhenThreeStraightNetBuyDaysReachThreePercentOfVolume() {
		// 순매수 합계 300, 거래량 합계 9000 -> 3.3333%
		InstitutionNetBuySignal.Result result = evaluate(
			candles("3000", "3000", "3000"), flows("100", "100", "100"));

		assertTrue(result.consecutiveNetBuy());
		assertTrue(result.ratioMet());
		assertTrue(result.signaled());
		assertEquals(0, new BigDecimal("300").compareTo(result.netBuySum()));
		assertEquals(0, new BigDecimal("9000").compareTo(result.volumeSum()));
		assertEquals(0, new BigDecimal("3.3333").compareTo(result.ratioPercent()));
	}

	@Test
	void exactlyThreePercentPassesAndJustBelowFails() {
		// 거래량 합계 9000의 3%는 정확히 270
		assertTrue(evaluate(candles("3000", "3000", "3000"), flows("90", "90", "90")).signaled());
		assertFalse(evaluate(candles("3000", "3000", "3000"), flows("90", "90", "89")).ratioMet());
	}

	@Test
	void aZeroOrNegativeDayBreaksTheStreakEvenIfTheSumIsLargeEnough() {
		// 합계는 충분(1000 이상)하지만 가운데 날이 0이거나 음수
		InstitutionNetBuySignal.Result zero = evaluate(
			candles("3000", "3000", "3000"), flows("500", "0", "500"));
		assertTrue(zero.ratioMet());
		assertFalse(zero.consecutiveNetBuy());
		assertFalse(zero.signaled());

		InstitutionNetBuySignal.Result negative = evaluate(
			candles("3000", "3000", "3000"), flows("800", "-10", "800"));
		assertFalse(negative.signaled());
	}

	@Test
	void onlyTheLatestThreeTradingDaysAreUsed() {
		// 4번째 날(더 과거)의 거래량과 순매수는 결과에 영향을 주지 않는다
		InstitutionNetBuySignal.Result result = evaluate(
			candles("3000", "3000", "3000", "999999999"), flows("100", "100", "100", "-5"));

		assertTrue(result.signaled());
		assertEquals(0, new BigDecimal("9000").compareTo(result.volumeSum()));
	}

	@Test
	void flowsDatedAfterTheSignalDayAreIgnored() {
		// 신호일 다음 날의 잠정치 기록이 섞여 들어와도 무시한다 (날짜를 캔들에서 정하기 때문)
		List<DailyFlow> flows = new ArrayList<>(flows("100", "100", "100"));
		flows.add(new DailyFlow(SIGNAL_DAY.plusDays(1), new BigDecimal("-999")));

		assertTrue(evaluate(candles("3000", "3000", "3000"), flows).signaled());
	}

	@Test
	void flowOrderDoesNotMatterBecauseRecordsAreMatchedByDate() {
		List<DailyFlow> reversed = new ArrayList<>(flows("500", "0", "500"));
		java.util.Collections.reverse(reversed);

		InstitutionNetBuySignal.Result result = evaluate(candles("3000", "3000", "3000"), reversed);

		assertFalse(result.consecutiveNetBuy());
		assertEquals(0, new BigDecimal("1000").compareTo(result.netBuySum()));
	}

	@Test
	void zeroVolumeSumMeansNoSignal() {
		InstitutionNetBuySignal.Result result = evaluate(
			candles("0", "0", "0"), flows("100", "100", "100"));

		assertFalse(result.ratioMet());
		assertFalse(result.signaled());
		assertEquals(0, BigDecimal.ZERO.compareTo(result.ratioPercent()));
	}

	@Test
	void missingFlowForATradingDayThrowsInsteadOfTreatingItAsZero() {
		List<DailyFlow> flows = flows("100", "100", "100");
		flows.remove(1);

		assertThrows(IllegalArgumentException.class,
			() -> evaluate(candles("3000", "3000", "3000"), flows));
	}

	@Test
	void duplicateFlowDatesThrow() {
		List<DailyFlow> flows = new ArrayList<>(flows("100", "100", "100"));
		flows.add(new DailyFlow(SIGNAL_DAY, new BigDecimal("100")));

		assertThrows(IllegalArgumentException.class,
			() -> evaluate(candles("3000", "3000", "3000"), flows));
	}

	@Test
	void tooFewCandlesThrowTheDedicatedExceptionSoCallersCanSkipOnlyThatCase() {
		assertThrows(InsufficientCandlesException.class,
			() -> evaluate(candles("3000", "3000"), flows("100", "100", "100")));
	}

	@Test
	void latestCandleMustBeTheExpectedSignalDay() {
		// 신호일 봉이 빠진(하루 전이 0번인) 목록으로 신호일을 판정하려 하면 예외
		List<Candle> staleList = candles("3000", "3000", "3000", "3000").subList(1, 4);

		assertThrows(IllegalArgumentException.class, () -> evaluate(staleList, flows("100", "100", "100", "100")));
	}

	@Test
	void resultRecordsTheSignalDay() {
		assertEquals(SIGNAL_DAY, evaluate(candles("3000", "3000", "3000"), flows("100", "100", "100")).signalDate());
	}

	@Test
	void oldestFirstCandlesThrow() {
		List<Candle> reversed = new ArrayList<>(candles("3000", "3000", "3000"));
		java.util.Collections.reverse(reversed);

		assertThrows(IllegalArgumentException.class,
			() -> evaluate(reversed, flows("100", "100", "100")));
	}
}
