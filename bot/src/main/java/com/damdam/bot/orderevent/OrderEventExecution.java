package com.damdam.bot.orderevent;

record OrderEventExecution(
	String filledQuantity,
	String averageFilledPrice,
	String filledAmount,
	String commission,
	String tax,
	String settlementDate
) {
}
