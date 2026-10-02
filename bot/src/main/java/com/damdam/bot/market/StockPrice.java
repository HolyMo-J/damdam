package com.damdam.bot.market;

// GET /api/v1/prices의 종목 하나. lastPrice는 소수 문자열 그대로 두고(Candle과 같은 방식), timestamp는 체결이 아직 없으면 null이다
public record StockPrice(
	String symbol,
	String timestamp,
	String lastPrice,
	String currency
) {
}
