package com.damdam.bot.liquidation;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderPlacementResult;
import com.damdam.bot.orders.OrderPlacementService;
import com.damdam.bot.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 최대 보유 5거래일(docs/strategy.md v0) 도달 시 자동 매도. 보유 시작 시점을 추적할 수 없는 기존 종목은 알림만
@Service
public class HoldingTimeExitService {

	private static final Logger log = LoggerFactory.getLogger(HoldingTimeExitService.class);
	private static final long MAX_HOLD_TRADING_DAYS = 5;
	private static final int ORDER_HISTORY_LOOKBACK_DAYS = 30;

	private final AccountService accountService;
	private final HoldingsService holdingsService;
	private final OrderService orderService;
	private final OrderPlacementService orderPlacementService;
	private final AutoSellGuard autoSellGuard;
	private final ManagedScopeGate managedScopeGate;
	private final BigDecimal maxAmountKrw;
	private final BigDecimal maxAmountUsd;
	private final TradingDayCalculator tradingDayCalculator = new TradingDayCalculator();
	private final PositionEntryResolver positionEntryResolver = new PositionEntryResolver();

	public HoldingTimeExitService(AccountService accountService, HoldingsService holdingsService,
			OrderService orderService, OrderPlacementService orderPlacementService, AutoSellGuard autoSellGuard,
			ManagedScopeGate managedScopeGate,
			@Value("${damdam.orders.max-amount-krw}") String maxAmountKrw,
			@Value("${damdam.orders.max-amount-usd}") String maxAmountUsd) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
		this.orderService = orderService;
		this.orderPlacementService = orderPlacementService;
		this.autoSellGuard = autoSellGuard;
		this.managedScopeGate = managedScopeGate;
		this.maxAmountKrw = new BigDecimal(maxAmountKrw);
		this.maxAmountUsd = new BigDecimal(maxAmountUsd);
	}

	public void checkAndAlert() {
		long accountSeq = accountService.getPrimaryAccountSeq();
		List<HoldingItem> items = holdingsService.getHoldings(accountSeq).items();

		for (HoldingItem item : items) {
			if (new BigDecimal(item.quantity()).signum() <= 0) {
				continue;
			}
			checkItem(accountSeq, item);
		}
	}

	private void checkItem(long accountSeq, HoldingItem item) {
		List<Order> closedOrders = orderService.getClosedOrders(accountSeq, item.symbol(), ORDER_HISTORY_LOOKBACK_DAYS);
		OffsetDateTime entryTime = positionEntryResolver.resolveEntryTime(closedOrders);

		if (entryTime == null) {
			// 조회 범위 안에서 매수 기록을 못 찾았다는 건, 그보다 더 오래전부터 보유 중이라는 뜻이다
			log.warn("[시간 청산 알림] {} 매수 체결일을 최근 {}일 안에서 찾지 못했습니다. 그보다 오래 보유 중이라 기준(5거래일)을 이미 넘었을 가능성이 높습니다. 청산을 검토하세요.",
				item.symbol(), ORDER_HISTORY_LOOKBACK_DAYS);
			return;
		}

		boolean withinManagedScope = entryTime.isAfter(managedScopeGate.scopeStart());

		long heldTradingDays = tradingDayCalculator.tradingDaysBetween(entryTime.toLocalDate(), LocalDate.now());
		if (heldTradingDays < MAX_HOLD_TRADING_DAYS) {
			return;
		}

		if (!withinManagedScope) {
			log.warn("[시간 청산 알림] {} 보유 {}거래일 경과 (매수 체결일: {}), 이 기능을 켜기 전부터 갖고 있던 종목이라 자동 매도 대상에서 제외합니다. 청산을 검토하세요.",
				item.symbol(), heldTradingDays, entryTime.toLocalDate());
			return;
		}

		log.warn("[시간 청산] {} 보유 {}거래일 경과 (매수 체결일: {}). 자동 매도를 시도합니다.",
			item.symbol(), heldTradingDays, entryTime.toLocalDate());
		attemptAutoSell(accountSeq, item);
	}

	private void attemptAutoSell(long accountSeq, HoldingItem item) {
		BigDecimal quantity = new BigDecimal(item.quantity());
		BigDecimal lastPrice = new BigDecimal(item.lastPrice());
		BigDecimal orderValue = quantity.multiply(lastPrice);
		boolean isKrw = "KRW".equals(item.currency());
		BigDecimal limit = isKrw ? maxAmountKrw : maxAmountUsd;

		if (orderValue.compareTo(limit) > 0) {
			log.warn("[시간 청산] {} 예상 주문 금액 {}{}이 한도({}{})를 넘어 자동 매도를 건너뜁니다. 직접 확인해주세요.",
				item.symbol(), orderValue, item.currency(), limit, item.currency());
			return;
		}

		if (!autoSellGuard.canPlaceAutoSell(item.symbol())) {
			return;
		}

		boolean isLoss = lastPrice.compareTo(new BigDecimal(item.averagePurchasePrice())) < 0;
		String clientOrderId = "time-exit-" + item.symbol() + "-" + LocalDate.now();
		OrderPlacementResult result = orderPlacementService.placeMarketSell(accountSeq, clientOrderId, item.symbol(), item.quantity());

		if (result.status() == OrderPlacementResult.Status.FAILED) {
			log.warn("[시간 청산] {} 자동 매도 실패: {}", item.symbol(), result.errorMessage());
			return;
		}
		autoSellGuard.recordAttempt(isLoss);
	}
}
