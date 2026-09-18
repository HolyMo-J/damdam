package com.damdam.bot.liquidation;

import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

// "이 기능을 처음 켠 시각 이후에 새로 산 종목"인지 판단한다 (ManagedScopeGate 참고).
// 그 전부터 갖고 있던 종목에는 봇이 자동으로 주문(OCO 포함)을 걸지 않는다
@Component
public class ManagedPositions {

	private static final int ORDER_HISTORY_LOOKBACK_DAYS = 30;

	private final OrderService orderService;
	private final ManagedScopeGate managedScopeGate;
	private final PositionEntryResolver positionEntryResolver = new PositionEntryResolver();

	public ManagedPositions(OrderService orderService, ManagedScopeGate managedScopeGate) {
		this.orderService = orderService;
		this.managedScopeGate = managedScopeGate;
	}

	// 보유 시작 시점을 찾지 못하면(조회 범위보다 오래 보유) 관리 대상이 아니다
	public boolean isManaged(long accountSeq, String symbol) {
		List<Order> closedOrders = orderService.getClosedOrders(accountSeq, symbol, ORDER_HISTORY_LOOKBACK_DAYS);
		OffsetDateTime entryTime = positionEntryResolver.resolveEntryTime(closedOrders);
		return entryTime != null && entryTime.isAfter(managedScopeGate.scopeStart());
	}
}
