package com.damdam.bot.papertrading;

// 신호 계산에 필요한 봉 수보다 적을 때(신규 상장 종목 등 정상적으로 생기는 상황)만 던진다.
// 호출하는 쪽이 이 예외만 잡아서 건너뛰고, 빈 값이나 순서 오류 같은 데이터 오류(IllegalArgumentException)는 삼키지 않게 하려고 분리했다
public class InsufficientCandlesException extends RuntimeException {

	public InsufficientCandlesException(String message) {
		super(message);
	}
}
