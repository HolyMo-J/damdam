package com.damdam.bot.conditionalorder;

import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.market.AtrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 매수 체결마다 평단가 기준으로 ATR 익절/손절 OCO를 등록하거나 기존 걸 수정한다 (docs/strategy.md v0)
@Service
public class AtrOcoManagementService {

	private static final Logger log = LoggerFactory.getLogger(AtrOcoManagementService.class);
	// 조건주문 만료일은 최대 보유 5거래일(시간 청산 기준)을 넉넉히 덮도록 잡는다. 그 전에 시간 청산이 대신 정리한다
	private static final long EXPIRE_DAYS = 10;

	private final HoldingsService holdingsService;
	private final AtrService atrService;
	private final ConditionalOrderService conditionalOrderService;

	public AtrOcoManagementService(HoldingsService holdingsService, AtrService atrService,
			ConditionalOrderService conditionalOrderService) {
		this.holdingsService = holdingsService;
		this.atrService = atrService;
		this.conditionalOrderService = conditionalOrderService;
	}

	// 매수 체결(신규/추가매수) 이벤트를 받으면 현재 평단가와 보유 수량으로 OCO를 최신화한다
	public void syncAfterBuyFill(long accountSeq, String symbol) {
		try {
			doSync(accountSeq, symbol);
		} catch (Exception e) {
			log.warn("[OCO 갱신] {} 처리 중 오류로 건너뜁니다: {}", symbol, e.getMessage());
		}
	}

	// 매도가 완전히 체결(FILL)된 뒤에 호출한다. 주문 접수 직후가 아니라 체결을 확인한 뒤에 정리해야,
	// 매도가 거부되거나 체결이 안 되는 동안에는 손절 보호(OCO)가 유지된다.
	// 보유가 0이면 남은 OCO를 취소하고, 일부만 팔린 경우에는 이미 있는 OCO의 수량만 남은 보유량에 맞춘다 (없던 OCO를 새로 만들지는 않는다)
	public void syncAfterSellFill(long accountSeq, String symbol) {
		try {
			Optional<HoldingItem> holding = findHolding(accountSeq, symbol);
			if (holding.isEmpty() || new BigDecimal(holding.get().quantity()).signum() <= 0) {
				cancelIfOpen(accountSeq, symbol);
				return;
			}
			if (conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol).isPresent()) {
				doSync(accountSeq, symbol);
			}
		} catch (Exception e) {
			log.warn("[OCO 정리] {} 매도 체결 후 처리 중 오류로 건너뜁니다: {}", symbol, e.getMessage());
		}
	}

	// 포지션이 0이 되면 남은 OCO를 정리한다
	public void cancelIfOpen(long accountSeq, String symbol) {
		try {
			conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol)
				.ifPresent(detail -> conditionalOrderService.cancelConditionalOrder(accountSeq, detail.conditionalOrderId()));
		} catch (Exception e) {
			log.warn("[OCO 정리] {} 처리 중 오류: {}", symbol, e.getMessage());
		}
	}

	private void doSync(long accountSeq, String symbol) {
		Optional<HoldingItem> holding = findHolding(accountSeq, symbol);
		if (holding.isEmpty() || new BigDecimal(holding.get().quantity()).signum() <= 0) {
			log.warn("[OCO 갱신] {} 보유 수량이 없어 건너뜁니다.", symbol);
			return;
		}
		HoldingItem item = holding.get();

		BigDecimal atr14 = atrService.getAtr14(symbol);
		boolean isKrw = "KRW".equals(item.currency());
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(new BigDecimal(item.averagePurchasePrice()), atr14, isKrw);
		String expireDate = LocalDate.now().plusDays(EXPIRE_DAYS).toString();

		Optional<ConditionalOrderDetail> existing = conditionalOrderService.findOpenConditionalOrder(accountSeq, symbol);
		if (existing.isPresent()) {
			conditionalOrderService.modifyAtrOco(accountSeq, existing.get().conditionalOrderId(), item.quantity(), expireDate,
				prices.takeProfitTrigger(), prices.takeProfitOrderPrice(), prices.stopLossTrigger(), prices.stopLossOrderPrice());
		} else {
			String clientOrderId = "atr-oco-" + symbol + "-" + System.currentTimeMillis();
			conditionalOrderService.createAtrOco(accountSeq, clientOrderId, symbol, item.quantity(), expireDate,
				prices.takeProfitTrigger(), prices.takeProfitOrderPrice(), prices.stopLossTrigger(), prices.stopLossOrderPrice());
		}
	}

	private Optional<HoldingItem> findHolding(long accountSeq, String symbol) {
		List<HoldingItem> items = holdingsService.getHoldings(accountSeq).items();
		return items.stream().filter(i -> i.symbol().equals(symbol)).findFirst();
	}
}
