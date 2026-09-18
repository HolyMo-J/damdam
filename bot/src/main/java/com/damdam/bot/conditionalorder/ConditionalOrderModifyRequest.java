package com.damdam.bot.conditionalorder;

record ConditionalOrderModifyRequest(
	String type,
	String quantity,
	String orderType,
	String expireDate,
	ConditionRequest first,
	ConditionRequest second
) {
}
