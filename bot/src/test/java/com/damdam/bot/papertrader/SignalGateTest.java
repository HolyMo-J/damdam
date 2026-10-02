package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.SignalScan;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.damdam.bot.papertrader.PaperTraderTestSupport.SIGNAL_DATE;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.basis;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kst;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.kstTime;
import static com.damdam.bot.papertrader.PaperTraderTestSupport.row;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalGateTest {

	private static final Instant RANKED_EVENING = kst("2026-10-02T20:17:34");   // 관찰값

	@Test
	void commonGateOpensForTheEveningRankingWhenRunAfterConfirmation() {
		SignalGate.Decision decision = SignalGate.common(SIGNAL_DATE, RANKED_EVENING, kstTime("2026-10-02T21:00:00"));

		assertTrue(decision.open());
		assertNull(decision.reason());
	}

	@Test
	void commonGateIsClosedWhenRunBeforeTheConfirmedTimeOnTheSignalDate() {
		SignalGate.Decision decision = SignalGate.common(SIGNAL_DATE, RANKED_EVENING, kstTime("2026-10-02T20:29:59"));

		assertFalse(decision.open());
		assertTrue(decision.reason().contains("확정 전"));
	}

	@Test
	void commonGateIsClosedWhenTheRankingTimeIsUnknown() {
		assertFalse(SignalGate.common(SIGNAL_DATE, null, kstTime("2026-10-02T21:00:00")).open());
	}

	@Test
	void commonGateBoundaryIsInclusiveAtTwentyFifteen() {
		assertFalse(SignalGate.common(SIGNAL_DATE, kst("2026-10-02T20:14:59"), kstTime("2026-10-02T21:00:00")).open());
		assertTrue(SignalGate.common(SIGNAL_DATE, kst("2026-10-02T20:15:00"), kstTime("2026-10-02T21:00:00")).open());
	}

	@Test
	void commonGateRejectsADaytimeProvisionalRanking() {
		assertFalse(SignalGate.common(SIGNAL_DATE, kst("2026-10-02T15:40:00"), kstTime("2026-10-02T21:00:00")).open());
	}

	@Test
	void commonGateRejectsANewerDaysRankingEvenIfItIsAfterTheThreshold() {
		// 다음 날 아침에 돌렸는데 순위가 이미 새 날짜 것이면 신호일의 순위가 아니다
		SignalGate.Decision decision = SignalGate.common(SIGNAL_DATE, kst("2026-10-03T09:30:00"), kstTime("2026-10-03T10:00:00"));

		assertFalse(decision.open());
	}

	@Test
	void commonGateStillOpensWhenRunedAfterMidnightWithTheSignalDatesRanking() {
		// PC가 늦게 켜져 자정 뒤에 돌아도 저녁 순위가 그대로이면(다음 날 06:50 갱신 전까지 관찰) 신호일 기준으로 판정할 수 있다
		SignalGate.Decision decision = SignalGate.common(SIGNAL_DATE, RANKED_EVENING, kstTime("2026-10-03T02:00:00"));

		assertTrue(decision.open());
	}

	@Test
	void commonGateIsClosedForASignalDateAfterToday() {
		assertFalse(SignalGate.common(SIGNAL_DATE.plusDays(3), RANKED_EVENING, kstTime("2026-10-02T21:00:00")).open());
	}

	@Test
	void institutionGateOpensWhenThereAreNoSignaledRowsEvenIfOtherRowsHaveOddTimes() {
		SignalScan.Row notSignaled = row(1, "A", false, false, kst("2026-10-02T09:00:00"), null);
		SignalScan.Row noRecord = row(2, "B", true, false, null, null);   // 전략 B만 신호가 났다

		assertTrue(SignalGate.institution(SIGNAL_DATE, java.util.List.of(notSignaled, noRecord)).open());
	}

	@Test
	void institutionGateOpensForASignaledRowUpdatedInTheEvening() {
		SignalScan.Row signaled = row(1, "A", false, true, kst("2026-10-02T20:21:22"), basis("100", "10000"));

		assertTrue(SignalGate.institution(SIGNAL_DATE, java.util.List.of(signaled)).open());
	}

	@Test
	void institutionGateIsClosedWhenASignaledRowWasUpdatedBeforeTheThreshold() {
		SignalScan.Row ok = row(1, "A", false, true, kst("2026-10-02T20:21:22"), basis("100", "10000"));
		SignalScan.Row early = row(2, "B", false, true, kst("2026-10-02T20:19:59"), basis("100", "10000"));

		SignalGate.Decision decision = SignalGate.institution(SIGNAL_DATE, java.util.List.of(ok, early));

		assertFalse(decision.open());
		assertTrue(decision.reason().contains("종목 B"));
	}

	@Test
	void institutionGateIsClosedWhenASignaledRowHasNoUpdateTimeOrIsFromAnotherDay() {
		SignalScan.Row noTime = row(1, "A", false, true, null, basis("100", "10000"));
		SignalScan.Row nextDay = row(1, "A", false, true, kst("2026-10-03T07:00:00"), basis("100", "10000"));

		assertFalse(SignalGate.institution(SIGNAL_DATE, java.util.List.of(noTime)).open());
		assertFalse(SignalGate.institution(SIGNAL_DATE, java.util.List.of(nextDay)).open());
	}
}
