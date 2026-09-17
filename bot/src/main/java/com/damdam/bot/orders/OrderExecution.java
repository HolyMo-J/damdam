package com.damdam.bot.orders;

record OrderExecution(
	String filledQuantity,
	String averageFilledPrice,
	String filledAmount,
	String commission,
	String tax,
	String filledAt,
	String settlementDate
) {
}
