package com.damdam.bot.liquidation;

import com.damdam.bot.orders.Order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

// 포지션이 0이었다가 다시 쌓이기 시작한 지점 이후의 첫 매수 체결 시각을 찾는다 (FIFO 기준)
class PositionEntryResolver {

	// orders는 시간순으로 정렬되어 있어야 한다
	OffsetDateTime resolveEntryTime(List<Order> orders) {
		BigDecimal running = BigDecimal.ZERO;
		OffsetDateTime entryTime = null;

		for (Order order : orders) {
			BigDecimal filledQuantity = parseFilledQuantity(order);
			if (filledQuantity.signum() == 0) {
				continue;
			}

			boolean wasFlat = running.signum() == 0;
			if ("BUY".equals(order.side())) {
				if (wasFlat) {
					entryTime = parseFillTime(order);
				}
				running = running.add(filledQuantity);
			} else {
				running = running.subtract(filledQuantity);
				if (running.signum() < 0) {
					running = BigDecimal.ZERO;
				}
			}
		}
		return entryTime;
	}

	private BigDecimal parseFilledQuantity(Order order) {
		String value = order.execution().filledQuantity();
		return value == null ? BigDecimal.ZERO : new BigDecimal(value);
	}

	private OffsetDateTime parseFillTime(Order order) {
		String filledAt = order.execution().filledAt();
		return OffsetDateTime.parse(filledAt != null ? filledAt : order.orderedAt());
	}
}
