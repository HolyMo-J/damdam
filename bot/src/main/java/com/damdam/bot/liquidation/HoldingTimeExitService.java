package com.damdam.bot.liquidation;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// 최대 보유 5거래일 규칙(docs/strategy.md v0)에 도달한 종목을 로그로 알린다. 자동 매도는 하지 않는다
@Service
public class HoldingTimeExitService {

	private static final Logger log = LoggerFactory.getLogger(HoldingTimeExitService.class);
	private static final long MAX_HOLD_TRADING_DAYS = 5;
	private static final int ORDER_HISTORY_LOOKBACK_DAYS = 30;

	private final AccountService accountService;
	private final HoldingsService holdingsService;
	private final OrderService orderService;
	private final TradingDayCalculator tradingDayCalculator = new TradingDayCalculator();
	private final PositionEntryResolver positionEntryResolver = new PositionEntryResolver();

	public HoldingTimeExitService(AccountService accountService, HoldingsService holdingsService, OrderService orderService) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
		this.orderService = orderService;
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

		long heldTradingDays = tradingDayCalculator.tradingDaysBetween(entryTime.toLocalDate(), LocalDate.now());
		if (heldTradingDays >= MAX_HOLD_TRADING_DAYS) {
			log.warn("[시간 청산 알림] {} 보유 {}거래일 경과 (매수 체결일: {}). 청산을 검토하세요.",
				item.symbol(), heldTradingDays, entryTime.toLocalDate());
		}
	}
}
