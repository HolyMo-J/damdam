package com.damdam.bot.orderevent;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.liquidation.ManagedPositions;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderService;
import com.damdam.bot.records.TradeRecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 웹소켓은 끊긴 구간의 이벤트를 다시 보내주지 않는다. 봇 시작 직후와 재연결 직후(구독이 확정된 뒤)에 REST로 상태를 다시 맞춘다:
// 1) 놓친 체결을 CSV에 기록 (같은 체결은 TradeRecordWriter가 중복 없이 걸러낸다)
// 2) 끊긴 사이 완전히 팔린 종목의 남은 OCO를 정리
// 3) 관리 범위 안의 보유 종목 중 OCO가 없거나 수량이 어긋난 것을 등록/수정 (관리 범위 밖의 기존 보유 종목은 건드리지 않는다)
@Service
public class OrderResyncService {

	private static final Logger log = LoggerFactory.getLogger(OrderResyncService.class);
	private static final int FILL_LOOKBACK_DAYS = 7;

	private final OrderService orderService;
	private final HoldingsService holdingsService;
	private final TradeRecordWriter tradeRecordWriter;
	private final AtrOcoManagementService atrOcoManagementService;
	private final ManagedPositions managedPositions;
	private final Notifier notifier;

	public OrderResyncService(OrderService orderService, HoldingsService holdingsService, TradeRecordWriter tradeRecordWriter,
			AtrOcoManagementService atrOcoManagementService, ManagedPositions managedPositions, Notifier notifier) {
		this.orderService = orderService;
		this.holdingsService = holdingsService;
		this.tradeRecordWriter = tradeRecordWriter;
		this.atrOcoManagementService = atrOcoManagementService;
		this.managedPositions = managedPositions;
		this.notifier = notifier;
	}

	public void resync(long accountSeq) {
		try {
			List<Order> orders = new ArrayList<>(orderService.getOpenOrders(accountSeq));
			orders.addAll(orderService.getRecentClosedOrders(accountSeq, FILL_LOOKBACK_DAYS));

			int recorded = 0;
			Set<String> newlyFullySoldSymbols = new LinkedHashSet<>();
			for (Order order : orders) {
				if (!hasFill(order)) {
					continue;
				}
				if (recordFill(order)) {
					recorded++;
					if ("SELL".equals(order.side()) && "FILLED".equals(order.status())) {
						newlyFullySoldSymbols.add(order.symbol());
					}
				}
			}

			for (String symbol : newlyFullySoldSymbols) {
				atrOcoManagementService.syncAfterSellFill(accountSeq, symbol);
			}
			int ocoFixed = ensureOcosForManagedHoldings(accountSeq);

			log.info("주문 재동기화 완료: 새로 기록한 체결 {}건, 보정한 OCO {}건", recorded, ocoFixed);
			if (recorded > 0 || ocoFixed > 0) {
				notifier.send("resync-found", "[담담] 재동기화로 끊긴 사이의 변화를 반영했습니다: 놓친 체결 " + recorded
					+ "건 기록, OCO " + ocoFixed + "건 보정.");
			}
		} catch (Exception e) {
			log.warn("주문 재동기화 실패: {}", e.getMessage());
			notifier.send("resync-failed", "[담담] 주문 재동기화에 실패했습니다. 끊긴 사이의 체결과 OCO 상태를 직접 확인하세요. 사유: " + e.getMessage());
		}
	}

	// OcoPeriodicCheckService(웹소켓 재연결 사이 정기 점검)에서도 같은 로직을 쓴다
	public int ensureOcosForManagedHoldings(long accountSeq) {
		int fixed = 0;
		List<HoldingItem> items = holdingsService.getHoldings(accountSeq).items();
		for (HoldingItem item : items) {
			if (new BigDecimal(item.quantity()).signum() <= 0) {
				continue;
			}
			try {
				if (managedPositions.isManaged(accountSeq, item.symbol())
						&& atrOcoManagementService.ensureOco(accountSeq, item.symbol())) {
					fixed++;
				}
			} catch (Exception e) {
				log.warn("[재동기화] {} OCO 확인 중 오류로 건너뜁니다: {}", item.symbol(), e.getMessage());
			}
		}
		return fixed;
	}

	private static boolean hasFill(Order order) {
		var execution = order.execution();
		if (execution == null || execution.filledQuantity() == null) {
			return false;
		}
		return new BigDecimal(execution.filledQuantity()).signum() > 0;
	}

	private boolean recordFill(Order order) {
		var execution = order.execution();
		String event = "FILLED".equals(order.status()) ? "FILL" : "PARTIAL_FILL";
		return tradeRecordWriter.record(order.orderId(), order.symbol(), order.side(), event,
			execution.filledQuantity(), execution.averageFilledPrice(), execution.filledAmount(),
			execution.commission(), execution.tax(), order.currency(), order.orderType(), order.status(),
			execution.filledAt(), TradeRecordWriter.SOURCE_RESYNC);
	}
}
