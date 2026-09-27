package com.damdam.bot.records;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.conditionalorder.AtrOcoPricing;
import com.damdam.bot.liquidation.HoldingTimeExitService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

// 매도 체결 한 건의 청산 사유를 추정한다. 웹소켓/REST 체결 이벤트에는 조건주문(OCO) ID나 어느 leg인지 알려주는 필드가 없어서
// (docs/review-tasks.md 8번, 2026-09-28 코드로 확인) 다른 서비스가 이미 들고 있는 상태로 역으로 추정한다.
// 서버가 확정해주는 값이 아니므로 확신이 없으면 MANUAL로 남긴다
@Component
public class ExitReasonClassifier {

	public static final String TAKE_PROFIT = "take_profit";
	public static final String STOP_LOSS = "stop_loss";
	public static final String TIME_EXIT = "time_exit";
	public static final String MANUAL = "manual";

	private final HoldingTimeExitService holdingTimeExitService;
	private final AtrOcoManagementService atrOcoManagementService;

	public ExitReasonClassifier(HoldingTimeExitService holdingTimeExitService, AtrOcoManagementService atrOcoManagementService) {
		this.holdingTimeExitService = holdingTimeExitService;
		this.atrOcoManagementService = atrOcoManagementService;
	}

	public String classifySell(String orderId, String symbol, String averageFilledPrice) {
		if (holdingTimeExitService.isPendingAutoSell(orderId)) {
			return TIME_EXIT;
		}
		Optional<AtrOcoPricing.Prices> prices = atrOcoManagementService.lastKnownPrices(symbol);
		if (prices.isEmpty()) {
			return MANUAL;
		}
		BigDecimal filled = new BigDecimal(averageFilledPrice);
		BigDecimal takeProfit = new BigDecimal(prices.get().takeProfitTrigger());
		BigDecimal stopLoss = new BigDecimal(prices.get().stopLossTrigger());
		if (filled.compareTo(takeProfit) >= 0) {
			return TAKE_PROFIT;
		}
		if (filled.compareTo(stopLoss) <= 0) {
			return STOP_LOSS;
		}
		return MANUAL;
	}
}
