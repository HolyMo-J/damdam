package com.damdam.bot.conditionalorder;

record ConditionalOrderCreateRequest(
	String symbol,
	String type,
	String quantity,
	String orderType,
	String clientOrderId,
	String expireDate,
	ConditionRequest first,
	ConditionRequest second
) {
}
