package com.damdam.bot.orders;

public record Order(
	String orderId,
	String symbol,
	String side,
	String orderType,
	String timeInForce,
	String status,
	String price,
	String quantity,
	String orderAmount,
	String currency,
	String orderedAt,
	String canceledAt,
	OrderExecution execution
) {
}
