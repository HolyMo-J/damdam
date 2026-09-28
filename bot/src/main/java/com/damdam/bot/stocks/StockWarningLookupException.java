package com.damdam.bot.stocks;

// 경고 조회가 끝내 실패했다는 뜻. 호출부는 "경고 없음"으로 착각하지 말고 그 종목을 통과시키지 않아야 한다.
// papertrading 소스는 RestClient 계열 이름을 못 쓰는 규칙이라, HTTP 예외는 여기서 이 예외로 감싸 넘긴다
public class StockWarningLookupException extends RuntimeException {

	public StockWarningLookupException(String symbol, String message, Throwable cause) {
		super("종목 %s 경고 조회 실패: %s".formatted(symbol, message), cause);
	}

	public StockWarningLookupException(String symbol, String message) {
		this(symbol, message, null);
	}
}
