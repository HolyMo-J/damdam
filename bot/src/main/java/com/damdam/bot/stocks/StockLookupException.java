package com.damdam.bot.stocks;

// 종목 단위 조회(경고, 일봉, 매매동향)가 끝내 실패했다는 뜻. 호출부는 이것을 "데이터 없음"이나 "신호 없음"으로 착각하지 말고
// 그 종목을 판정하지 않은 것으로 다뤄야 한다. papertrading 소스는 RestClient 계열 이름을 못 쓰는 규칙이라,
// HTTP 예외는 stocks 패키지에서 이 예외로 감싸 넘긴다
public class StockLookupException extends RuntimeException {

	public StockLookupException(String symbol, String what, String message, Throwable cause) {
		super("종목 %s %s 실패: %s".formatted(symbol, what, message), cause);
	}

	public StockLookupException(String symbol, String what, String message) {
		this(symbol, what, message, null);
	}
}
