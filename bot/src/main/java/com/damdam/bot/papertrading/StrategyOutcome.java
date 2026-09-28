package com.damdam.bot.papertrading;

// 한 종목에 대한 한 전략의 판정 결과. "신호 없음"과 "판정하지 못함"을 구분하려고 상태를 함께 둔다.
// EVALUATED일 때만 result가 있고, 나머지는 detail에 이유가 있다
public record StrategyOutcome<T>(Status status, T result, String detail) {

	public enum Status {
		EVALUATED,             // 판정을 끝냄 (신호가 났는지는 result가 알려줌)
		INSUFFICIENT_CANDLES,  // 봉이 모자람 (신규 상장 등). 정상적으로 건너뜀
		DATA_ERROR,            // 입력 데이터가 어긋남 (날짜 불일치, 기록 누락, 순서 오류 등). 신호로 쓰면 안 됨
		FETCH_FAILED           // 조회가 끝내 실패
	}

	static <T> StrategyOutcome<T> evaluated(T result) {
		return new StrategyOutcome<>(Status.EVALUATED, result, null);
	}

	static <T> StrategyOutcome<T> insufficientCandles(String detail) {
		return new StrategyOutcome<>(Status.INSUFFICIENT_CANDLES, null, detail);
	}

	static <T> StrategyOutcome<T> dataError(String detail) {
		return new StrategyOutcome<>(Status.DATA_ERROR, null, detail);
	}

	static <T> StrategyOutcome<T> fetchFailed(String detail) {
		return new StrategyOutcome<>(Status.FETCH_FAILED, null, detail);
	}
}
