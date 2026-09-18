package com.damdam.bot.conditionalorder;

public record ConditionalOrderCondition(
	String type,
	String status,
	String triggerPrice,
	String targetProfitRate,
	String orderPrice,
	String triggeredOrderId
) {
}
