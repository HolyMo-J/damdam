package com.damdam.bot.papertrader;

import com.damdam.bot.papertrading.SignalDates;
import com.damdam.bot.papertrading.SignalScan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

// 신호 시각 검사: 신호일 저녁 확정 뒤의 데이터로 판정했을 때만 신호를 대기 신호로 저장한다. 아니면 그날은 "공백"으로 남기고
// 신호를 만들지 않는다. 신호 없음과 구분하려는 것이다 (docs/strategy.md "실행 방식", 잠정치로 난 신호가 나중에 뒤집히는 것을 막는다).
//
// 기준 시각은 관찰값에서 정했다 (docs/measurements.md "데이터 확정 시각": 순위 rankedAt 20:17:34~20:17:47, 신호가 난 종목의
// 매매동향 updatedAt 20:21:22~20:23:45, 관찰 3일). 관찰한 최솟값보다 조금 이른 값이라, 값이 더 쌓여 최솟값이 이 기준보다
// 이르면 정상인 날이 공백으로 막힌다 (신호를 줄이는 방향).
//
// 실제로 무엇을 거르는가 (2026-10-03 독립 검토로 확인): 러너는 신호일이 오늘이면 20:30 이후에만 신호일로 고르므로 20:15와 20:20
// 기준은 그 경로에서 항상 통과한다. 실질적으로 거르는 것은 "순위와 기록의 날짜가 신호일과 같은가"다. 다음 날 아침 이후에 돌렸을 때
// 새 날짜의 순위나 기록이 섞이는 것을 막고, 낮에 갱신된 잠정 기록(신호일 20:20 이전)이 남아 있는 경우를 거른다.
// "저녁 값이 확정치"라는 근거는 저녁부터 다음 날 03~05시까지 값이 안 바뀐 관찰뿐이고, 06:50 갱신이 값을 바꾸는지는 확인 못 함이다
final class SignalGate {

	static final LocalTime RANKING_FINAL_FROM = LocalTime.of(20, 15);
	static final LocalTime FLOW_FINAL_FROM = LocalTime.of(20, 20);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// open이면 reason은 null이고, 닫혔으면 사람이 읽는 사유가 있다 (쉼표와 줄바꿈 없이, 기록 파일에 그대로 남겨도 되는 문장)
	record Decision(boolean open, String reason) {

		static Decision passed() {
			return new Decision(true, null);
		}

		static Decision closed(String reason) {
			return new Decision(false, reason);
		}
	}

	private SignalGate() {
	}

	// 두 전략에 공통인 검사: 실행 시각과 순위 집계 시각. 일봉(전략 B)은 확정 시각을 모르므로 실행 시각 기준(20:30 이후)이 유일한 근거다
	static Decision common(LocalDate signalDate, Instant rankedAt, ZonedDateTime now) {
		if (signalDate.isAfter(now.toLocalDate())) {
			return Decision.closed("신호일 " + signalDate + "이 오늘보다 뒤입니다");
		}
		if (signalDate.equals(now.toLocalDate()) && now.toLocalTime().isBefore(SignalDates.CONFIRMED_AFTER)) {
			return Decision.closed("신호일이 오늘인데 " + SignalDates.CONFIRMED_AFTER + " 전이라 확정 전입니다");
		}
		if (rankedAt == null) {
			return Decision.closed("순위 집계 시각을 알 수 없습니다");
		}
		ZonedDateTime ranked = rankedAt.atZone(KST);
		if (!isFinalOn(ranked, signalDate, RANKING_FINAL_FROM)) {
			return Decision.closed("순위 집계 시각 " + format(ranked) + "이 신호일 " + signalDate + " " + RANKING_FINAL_FROM + " 이후가 아닙니다");
		}
		return Decision.passed();
	}

	// 전략 A 추가 검사: 신호가 난 종목의 신호일 매매동향 기록 갱신 시각. 하나라도 이르면 전략 A는 그날 전체를 공백으로 둔다
	// (일부 종목만 쓰면 표본이 편향된다). 신호가 안 난 종목은 보지 않는다: 갱신 시각이 어긋난 종목(저유동 종목 등)이 있어도
	// 그날 신호와 상관없으면 막지 않으려는 것이고, 그 값은 scan_rows.csv에 남아 나중에 볼 수 있다
	static Decision institution(LocalDate signalDate, List<SignalScan.Row> rows) {
		for (SignalScan.Row row : rows) {
			if (!row.institutionSignaled()) {
				continue;
			}
			Instant updatedAt = row.institutionRecordUpdatedAt();
			if (updatedAt == null) {
				return Decision.closed("종목 " + row.symbol() + "의 매매동향 갱신 시각이 없습니다");
			}
			ZonedDateTime updated = updatedAt.atZone(KST);
			if (!isFinalOn(updated, signalDate, FLOW_FINAL_FROM)) {
				return Decision.closed("종목 " + row.symbol() + "의 매매동향 갱신 시각 " + format(updated) + "이 신호일 "
					+ signalDate + " " + FLOW_FINAL_FROM + " 이후가 아닙니다");
			}
		}
		return Decision.passed();
	}

	private static boolean isFinalOn(ZonedDateTime time, LocalDate signalDate, LocalTime from) {
		return time.toLocalDate().equals(signalDate) && !time.toLocalTime().isBefore(from);
	}

	private static String format(ZonedDateTime time) {
		return time.toLocalDate() + " " + time.toLocalTime().withNano(0) + " (UTC" + ZoneOffset.ofHours(9) + ")";
	}
}
