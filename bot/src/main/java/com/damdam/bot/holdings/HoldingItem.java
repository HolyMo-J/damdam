package com.damdam.bot.holdings;

record HoldingItem(
	String symbol,
	String name,
	String marketCountry,
	String currency,
	String quantity,
	String lastPrice,
	String averagePurchasePrice,
	HoldingItemMarketValue marketValue,
	HoldingItemProfitLoss profitLoss,
	HoldingItemDailyProfitLoss dailyProfitLoss,
	HoldingItemCost cost
) {
}
