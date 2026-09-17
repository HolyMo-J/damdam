package com.damdam.bot.liquidation;

// 연속 손실 횟수와 정지 여부를 재시작해도 유지하기 위해 파일로 저장한다
record GuardState(int consecutiveLosses, boolean paused) {

	static GuardState initial() {
		return new GuardState(0, false);
	}
}
