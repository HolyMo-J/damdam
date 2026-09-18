package com.damdam.bot.conditionalorder;

public record ConditionalOrderDetail(
	String conditionalOrderId,
	String type,
	String status,
	String symbol,
	String market,
	String quantity,
	String orderType,
	String expireDate,
	ConditionalOrderCondition first,
	ConditionalOrderCondition second,
	String createdAt
) {
}
