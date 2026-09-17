package com.damdam.bot.liquidation;

import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderExecution;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PositionEntryResolverTest {

	private final PositionEntryResolver resolver = new PositionEntryResolver();

	@Test
	void noOrdersMeansNoEntryTime() {
		assertNull(resolver.resolveEntryTime(List.of()));
	}

	@Test
	void singleBuyIsTheEntryTime() {
		Order buy = order("BUY", "10", "2026-03-01T09:30:00+09:00");
		assertEquals(OffsetDateTime.parse("2026-03-01T09:30:00+09:00"), resolver.resolveEntryTime(List.of(buy)));
	}

	@Test
	void partialSellDoesNotResetEntryTime() {
		Order buy = order("BUY", "10", "2026-03-01T09:30:00+09:00");
		Order partialSell = order("SELL", "4", "2026-03-02T09:30:00+09:00");
		OffsetDateTime entryTime = resolver.resolveEntryTime(List.of(buy, partialSell));

		assertEquals(OffsetDateTime.parse("2026-03-01T09:30:00+09:00"), entryTime);
	}

	@Test
	void fullExitThenRebuyResetsEntryTime() {
		Order firstBuy = order("BUY", "10", "2026-03-01T09:30:00+09:00");
		Order fullSell = order("SELL", "10", "2026-03-02T09:30:00+09:00");
		Order secondBuy = order("BUY", "5", "2026-03-05T09:30:00+09:00");

		OffsetDateTime entryTime = resolver.resolveEntryTime(List.of(firstBuy, fullSell, secondBuy));

		assertEquals(OffsetDateTime.parse("2026-03-05T09:30:00+09:00"), entryTime);
	}

	private Order order(String side, String filledQuantity, String filledAt) {
		OrderExecution execution = new OrderExecution(filledQuantity, "70000", "700000", "1400", "0", filledAt, "2026-03-03");
		return new Order("order-id", "005930", side, "LIMIT", "DAY", "FILLED", "70000",
			filledQuantity, null, "KRW", filledAt, null, execution);
	}
}
