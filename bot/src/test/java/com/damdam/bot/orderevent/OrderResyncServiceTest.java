package com.damdam.bot.orderevent;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsOverview;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.liquidation.ManagedPositions;
import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderExecution;
import com.damdam.bot.orders.OrderService;
import com.damdam.bot.records.TradeRecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 협력 객체는 모두 가짜다. 실제 API는 호출되지 않고, CSV는 임시 폴더에만 쓴다
class OrderResyncServiceTest {

	private static final long ACCOUNT = 1L;

	@TempDir
	Path tempDir;

	private OrderService orderService;
	private HoldingsService holdingsService;
	private AtrOcoManagementService atrOcoManagementService;
	private ManagedPositions managedPositions;
	private final List<String> alerts = new ArrayList<>();
	private Path csv;
	private OrderResyncService service;

	@BeforeEach
	void setUp() {
		orderService = mock(OrderService.class);
		holdingsService = mock(HoldingsService.class);
		atrOcoManagementService = mock(AtrOcoManagementService.class);
		managedPositions = mock(ManagedPositions.class);
		csv = tempDir.resolve("trades.csv");
		service = new OrderResyncService(orderService, holdingsService, new TradeRecordWriter(csv.toString()),
			atrOcoManagementService, managedPositions, (key, message) -> alerts.add(key));

		when(orderService.getOpenOrders(ACCOUNT)).thenReturn(List.of());
		when(orderService.getRecentClosedOrders(anyLong(), anyInt())).thenReturn(List.of());
		givenHoldings();
	}

	private void givenHoldings(HoldingItem... items) {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null, List.of(items)));
	}

	private static HoldingItem holding(String symbol) {
		return new HoldingItem(symbol, "테스트", "US", "USD", "5", "100", "100", null, null, null, null);
	}

	private static Order order(String orderId, String symbol, String side, String status, String filledQuantity) {
		return new Order(orderId, symbol, side, "LIMIT", "DAY", status, "100", "10", null, "USD",
			"2026-09-21T10:00:00+09:00", null,
			new OrderExecution(filledQuantity, "100", "1000", "1", "0", "2026-09-21T10:01:00+09:00", "2026-09-23"));
	}

	private long csvRows() throws IOException {
		return Files.readAllLines(csv).size() - 1;
	}

	@Test
	void recordsFillsMissedWhileDisconnected() throws IOException {
		when(orderService.getRecentClosedOrders(ACCOUNT, 7)).thenReturn(List.of(
			order("o-1", "AAA", "BUY", "FILLED", "10"),
			order("o-2", "BBB", "BUY", "CANCELED", "0")));

		service.resync(ACCOUNT);

		assertEquals(1, csvRows());
		String row = Files.readAllLines(csv).get(1);
		assertTrue(row.contains(",o-1,AAA,BUY,FILL,10,"));
		// 재동기화 행은 실제 체결 시각과 출처를 남긴다
		assertTrue(row.endsWith(",2026-09-21T10:01:00+09:00,resync"));
		assertTrue(alerts.contains("resync-found"));
	}

	// 웹소켓으로 이미 기록한 체결은 재동기화가 다시 기록하지 않는다
	@Test
	void doesNotDuplicateFillsAlreadyRecordedByTheStream() throws IOException {
		new TradeRecordWriter(csv.toString()).record("o-1", "AAA", "BUY", "FILL", "10", "100", "1000", "1", "0",
			"USD", "LIMIT", "FILLED");
		when(orderService.getRecentClosedOrders(ACCOUNT, 7)).thenReturn(List.of(order("o-1", "AAA", "BUY", "FILLED", "10.000")));
		service = new OrderResyncService(orderService, holdingsService, new TradeRecordWriter(csv.toString()),
			atrOcoManagementService, managedPositions, (key, message) -> alerts.add(key));

		service.resync(ACCOUNT);
		service.resync(ACCOUNT);

		assertEquals(1, csvRows());
		assertTrue(alerts.isEmpty());
	}

	@Test
	void recordsPartialFillsOfStillOpenOrders() throws IOException {
		when(orderService.getOpenOrders(ACCOUNT)).thenReturn(List.of(order("o-3", "AAA", "BUY", "PARTIAL_FILLED", "3")));

		service.resync(ACCOUNT);

		assertTrue(Files.readAllLines(csv).get(1).contains(",o-3,AAA,BUY,PARTIAL_FILL,3,"));
	}

	// 끊긴 사이 완전히 팔린 종목은 남은 OCO를 정리한다
	@Test
	void cleansUpTheOcoOfAPositionSoldWhileDisconnected() {
		when(orderService.getRecentClosedOrders(ACCOUNT, 7)).thenReturn(List.of(order("o-4", "AAA", "SELL", "FILLED", "5")));

		service.resync(ACCOUNT);

		verify(atrOcoManagementService).syncAfterSellFill(ACCOUNT, "AAA");
	}

	@Test
	void registersMissingOcosOnlyForManagedHoldings() {
		givenHoldings(holding("MANAGED"), holding("OLD"));
		when(managedPositions.isManaged(ACCOUNT, "MANAGED")).thenReturn(true);
		when(managedPositions.isManaged(ACCOUNT, "OLD")).thenReturn(false);
		when(atrOcoManagementService.ensureOco(ACCOUNT, "MANAGED")).thenReturn(true);

		service.resync(ACCOUNT);

		verify(atrOcoManagementService).ensureOco(ACCOUNT, "MANAGED");
		// 그 전부터 갖고 있던 종목에는 봇이 OCO를 걸지 않는다
		verify(atrOcoManagementService, never()).ensureOco(ACCOUNT, "OLD");
		assertTrue(alerts.contains("resync-found"));
	}

	@Test
	void aFailureIsReportedNotThrown() {
		when(orderService.getOpenOrders(ACCOUNT)).thenThrow(new IllegalStateException("주문 조회 실패"));

		service.resync(ACCOUNT);

		assertEquals(List.of("resync-failed"), alerts);
	}
}
