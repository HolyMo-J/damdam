package com.damdam.bot.liquidation;

// 연속 손실 횟수, 정지 여부, 하루 자동 매도 횟수를 재시작해도 유지하기 위해 파일로 저장한다.
// dailyCountDate는 ISO 날짜 문자열이고, 오늘 날짜와 다르면 하루 횟수는 0으로 본다.
// 옛 형식 파일에는 두 필드가 없다. Jackson 3는 없는 값을 int에 넣으면 실패하므로 dailyCount만 Integer로 두고,
// 핵심 필드(consecutiveLosses, paused)는 일부러 엄격하게 둬서 손상된 파일이 조용히 0으로 읽히지 않게 한다
record GuardState(int consecutiveLosses, boolean paused, String dailyCountDate, Integer dailyCount) {

	static GuardState initial() {
		return new GuardState(0, false, null, 0);
	}

	// 상태 파일을 읽지 못했을 때 쓰는 값. 모르는 상태에서 주문이 나가는 것보다 멈추는 쪽이 안전하다
	static GuardState unreadable() {
		return new GuardState(0, true, null, 0);
	}

	int dailyCountOn(String today) {
		return today.equals(dailyCountDate) && dailyCount != null ? dailyCount : 0;
	}
}
