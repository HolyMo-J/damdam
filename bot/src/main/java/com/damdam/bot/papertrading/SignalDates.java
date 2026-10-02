package com.damdam.bot.papertrading;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.function.Predicate;

// 신호일 기본값을 정하는 규칙. 신호 조회 러너(signal-scan)와 가상매매 러너(paper-run)가 같은 규칙을 쓰도록 한곳에 둔다.
// 오늘이 영업일이고 20:30 이후면 오늘, 아니면 직전 영업일. 20:30은 순위와 기관 매매동향이 저녁에 확정되는 시각을
// 관찰(순위 20:17, 매매동향 20:21~20:23)한 값에 여유를 둔 보수적 기준이다 (docs/measurements.md "데이터 확정 시각")
public final class SignalDates {

	public static final LocalTime CONFIRMED_AFTER = LocalTime.of(20, 30);
	private static final int MAX_LOOKBACK_DAYS = 15;

	private SignalDates() {
	}

	// 영업일 판정은 호출하는 쪽이 넘긴다 (이 클래스는 캘린더 API를 모른다)
	public static LocalDate defaultSignalDate(ZonedDateTime now, Predicate<LocalDate> isTradingDay) {
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
}
