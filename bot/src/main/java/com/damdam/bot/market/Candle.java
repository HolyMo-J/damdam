package com.damdam.bot.market;

public record Candle(
	String timestamp,
	String openPrice,
	String highPrice,
	String lowPrice,
	String closePrice,
	String volume,
	String currency
) {
}
