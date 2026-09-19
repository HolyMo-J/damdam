package com.damdam.bot.liquidation;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.control.TradingHaltSwitch;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.notification.Notifier;
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
	private final TradingHaltSwitch haltSwitch;
	private final Notifier notifier;
	private final BigDecimal maxAmountKrw;
	private final BigDecimal maxAmountUsd;
	private final TradingDayCalculator tradingDayCalculator = new TradingDayCalculator();
	private final PositionEntryResolver positionEntryResolver = new PositionEntryResolver();

	public HoldingTimeExitService(AccountService accountService, HoldingsService holdingsService,
			OrderService orderService, OrderPlacementService orderPlacementService, AutoSellGuard autoSellGuard,
			ManagedScopeGate managedScopeGate, TradingHaltSwitch haltSwitch, Notifier notifier,
			@Value("${damdam.orders.max-amount-krw}") String maxAmountKrw,
			@Value("${damdam.orders.max-amount-usd}") String maxAmountUsd) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
		this.orderService = orderService;
		this.orderPlacementService = orderPlacementService;
		this.autoSellGuard = autoSellGuard;
		this.managedScopeGate = managedScopeGate;
		this.haltSwitch = haltSwitch;
		this.notifier = notifier;
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
			notifier.send("time-exit-noentry-" + item.symbol(), "[담담] " + item.symbol() + " 매수 체결일을 최근 "
				+ ORDER_HISTORY_LOOKBACK_DAYS + "일 안에서 찾지 못했습니다. 그보다 오래 보유 중이라 기준(5거래일)을 이미 넘었을 가능성이 높습니다. 청산을 검토하세요.");
			return;
		}

		long heldTradingDays = tradingDayCalculator.tradingDaysBetween(entryTime.toLocalDate(), LocalDate.now());
		if (heldTradingDays < MAX_HOLD_TRADING_DAYS) {
			return;
		}

		if (!"KRW".equals(item.currency())) {
			// 해외 종목은 자동 매도하지 않는다 (docs/strategy.md, 2026-09-19 결정). 관리 범위(ManagedScopeGate)와 무관하게 항상 알린다
			log.warn("[시간 청산 알림] {} 보유 {}거래일 경과 (매수 체결일: {}), 해외 종목이라 자동 매도 대상에서 제외하고 알림만 보냅니다.",
				item.symbol(), heldTradingDays, entryTime.toLocalDate());
			notifier.send("time-exit-overseas-" + item.symbol(), "[담담] " + item.symbol() + " 보유 " + heldTradingDays
				+ "거래일 경과 (매수 체결일: " + entryTime.toLocalDate() + "). 해외 종목은 자동 매도하지 않으니 직접 확인하세요.");
			return;
		}

		boolean withinManagedScope = entryTime.isAfter(managedScopeGate.scopeStart());
		if (!withinManagedScope) {
			log.warn("[시간 청산 알림] {} 보유 {}거래일 경과 (매수 체결일: {}), 이 기능을 켜기 전부터 갖고 있던 종목이라 자동 매도 대상에서 제외합니다. 청산을 검토하세요.",
				item.symbol(), heldTradingDays, entryTime.toLocalDate());
			notifier.send("time-exit-scope-" + item.symbol(), "[담담] " + item.symbol() + " 보유 " + heldTradingDays
				+ "거래일 경과 (매수 체결일: " + entryTime.toLocalDate() + "). 이 기능을 켜기 전부터 갖고 있던 종목이라 자동 매도 대상에서 제외했습니다. 직접 확인하세요.");
			return;
		}

		log.warn("[시간 청산] {} 보유 {}거래일 경과 (매수 체결일: {}). 자동 매도를 시도합니다.",
			item.symbol(), heldTradingDays, entryTime.toLocalDate());
		attemptAutoSell(accountSeq, item);
	}

	private void attemptAutoSell(long accountSeq, HoldingItem item) {
		if (haltSwitch.isHalted()) {
			log.warn("[시간 청산] 정지 파일이 있어 {} 자동 매도를 건너뜁니다.", item.symbol());
			notifier.send("halt-exit-" + item.symbol(), "[담담] 정지 파일 때문에 " + item.symbol() + " 시간 청산 매도를 건너뛰었습니다. 직접 확인하세요.");
			return;
		}
		BigDecimal quantity = new BigDecimal(item.quantity());
		BigDecimal lastPrice = new BigDecimal(item.lastPrice());
		BigDecimal orderValue = quantity.multiply(lastPrice);
		boolean isKrw = "KRW".equals(item.currency());
		BigDecimal limit = isKrw ? maxAmountKrw : maxAmountUsd;

		if (orderValue.compareTo(limit) > 0) {
			log.warn("[시간 청산] {} 예상 주문 금액 {}{}이 한도({}{})를 넘어 자동 매도를 건너뜁니다. 직접 확인해주세요.",
				item.symbol(), orderValue, item.currency(), limit, item.currency());
			notifier.send("time-exit-limit-" + item.symbol(), "[담담] " + item.symbol() + " 예상 주문 금액 " + orderValue
				+ item.currency() + "이 한도(" + limit + item.currency() + ")를 넘어 시간 청산 자동 매도를 건너뛰었습니다. 이 포지션은 한도 안으로 줄이기 전까지 계속 건너뛰어집니다. 직접 확인하세요.");
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
			// 다음 재시도는 다음 영업일 스케줄까지 없으므로, 실패했다는 사실을 반드시 바로 알린다
			notifier.send("time-exit-failed-" + item.symbol(), "[담담] " + item.symbol() + " 시간 청산 자동 매도가 실패했습니다: "
				+ result.errorMessage() + ". 다음 재시도는 다음 영업일이니 직접 확인하세요.");
			return;
		}
		if (result.status() == OrderPlacementResult.Status.SIMULATED) {
			// 모의 실행은 안전장치 기록에 남기지 않는다. 남기면 실제로 팔리지 않은 손실이 쌓여 실전 전환 때 이미 정지 상태가 된다
			log.info("[시간 청산] {} 모의 실행이라 안전장치 기록과 OCO 정리를 건너뜁니다.", item.symbol());
			return;
		}
		log.warn("[시간 청산] {} 자동 매도 주문 접수됨 (orderId={})", item.symbol(), result.orderId());
		notifier.send("time-exit-placed-" + item.symbol(), "[담담] " + item.symbol() + " 시간 청산 자동 매도 주문이 접수됐습니다 (orderId="
			+ result.orderId() + "). 체결되면 별도로 알립니다.");
		autoSellGuard.recordAttempt(isLoss);
		// OCO는 여기서 취소하지 않는다. 접수만 된 상태에서 취소하면 매도가 거부되거나 안 팔려도 손절 보호가 사라진다.
		// 매도 체결(FILL) 이벤트를 받은 뒤 AtrOcoManagementService.syncAfterSellFill이 정리한다
	}
}
