package com.damdam.bot.stocks;

// 경고 조회가 끝내 실패했다는 뜻. 호출부는 "경고 없음"으로 착각하지 말고 그 종목을 통과시키지 않아야 한다
public class StockWarningLookupException extends StockLookupException {

	public StockWarningLookupException(String symbol, String message, Throwable cause) {
		super(symbol, "경고 조회", message, cause);
	}

	public StockWarningLookupException(String symbol, String message) {
		this(symbol, message, null);
	}
}
