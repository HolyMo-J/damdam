package com.damdam.bot.holdings;

import java.util.List;

public record HoldingsOverview(
	CurrencyAmount totalPurchaseAmount,
	MarketValueSummary marketValue,
	ProfitLossSummary profitLoss,
	DailyProfitLossSummary dailyProfitLoss,
	List<HoldingItem> items
) {
}
