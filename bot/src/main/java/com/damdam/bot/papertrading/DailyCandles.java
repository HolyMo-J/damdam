package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 신호 판정 함수들이 공통으로 쓰는 일봉 입력 검증
final class DailyCandles {

	private DailyCandles() {
	}

	// 일봉의 timestamp는 그 종목 시장의 현지 자정 고정이라(AtrService 참고), 오프셋을 그대로 둔 채 날짜만 꺼내면 현지 거래일이다
	static LocalDate dateOf(Candle candle) {
		return OffsetDateTime.parse(candle.timestamp()).toLocalDate();
	}

	// 0번 봉의 날짜가 기대한 신호일과 같아야 한다
	static void requireLatestDate(List<Candle> candlesNewestFirst, LocalDate expectedSignalDate) {
		LocalDate latest = dateOf(candlesNewestFirst.get(0));
		if (!latest.equals(expectedSignalDate)) {
			throw new IllegalArgumentException("가장 최근 봉의 날짜(%s)가 기대한 신호일(%s)과 다릅니다.".formatted(latest, expectedSignalDate));
		}
	}

	// 최신순(내림차순)이 아니거나 같은 날짜가 두 번 나오면 예외. 순서가 뒤집히면 "직전 N일" 계산이 조용히 틀어지기 때문이다
	static void requireStrictlyDescending(List<Candle> candlesNewestFirst) {
		OffsetDateTime newer = OffsetDateTime.parse(candlesNewestFirst.get(0).timestamp());
		for (int i = 1; i < candlesNewestFirst.size(); i++) {
			OffsetDateTime older = OffsetDateTime.parse(candlesNewestFirst.get(i).timestamp());
			if (!older.isBefore(newer)) {
				throw new IllegalArgumentException(
					"캔들이 최신순이 아니거나 날짜가 중복됩니다 (인덱스 %d: %s, 그 앞: %s).".formatted(i, older, newer));
			}
			newer = older;
		}
	}
}
