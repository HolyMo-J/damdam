package com.damdam.bot.holdings;

import java.util.List;

record HoldingsOverview(
	CurrencyAmount totalPurchaseAmount,
	MarketValueSummary marketValue,
	ProfitLossSummary profitLoss,
	DailyProfitLossSummary dailyProfitLoss,
	List<HoldingItem> items
) {
}
