package com.damdam.bot.liquidation;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.control.TradingHaltSwitch;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsOverview;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.orders.Order;
import com.damdam.bot.orders.OrderExecution;
import com.damdam.bot.orders.OrderPlacementResult;
import com.damdam.bot.orders.OrderPlacementService;
import com.damdam.bot.orders.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 해외 종목은 5거래일이 지나도 자동 매도하지 않고 알림만 보낸다는 규칙(docs/strategy.md, 2026-09-19 결정)을 검증한다
class HoldingTimeExitServiceTest {

	private static final long ACCOUNT = 1L;
	private static final String SYMBOL = "AAA";
	// 주말이 껴 있어도 5거래일 조건을 넉넉히 넘기도록 10일 전으로 잡는다
	private static final OffsetDateTime OLD_ENTRY = OffsetDateTime.now().minusDays(10);

	private AccountService accountService;
	private HoldingsService holdingsService;
	private OrderService orderService;
	private OrderPlacementService orderPlacementService;
	private AutoSellGuard autoSellGuard;
	private ManagedScopeGate managedScopeGate;
	private TradingHaltSwitch haltSwitch;
	private final List<String> alerts = new ArrayList<>();
	private final Notifier fakeNotifier = (key, message) -> alerts.add(key);

	@BeforeEach
	void setUp(@TempDir Path tempDir) throws IOException {
		accountService = mock(AccountService.class);
		holdingsService = mock(HoldingsService.class);
		orderService = mock(OrderService.class);
		orderPlacementService = mock(OrderPlacementService.class);
		autoSellGuard = new AutoSellGuard(10, 10, tempDir.resolve("guard.json").toString(),
			java.time.Clock.systemDefaultZone(), fakeNotifier);
		// 관리 범위 시작 시각을 과거로 미리 적어둬서, OLD_ENTRY(매수 체결일)가 항상 관리 범위 안에 들게 한다
		Path scopeStartFile = tempDir.resolve("scope-start");
		Files.writeString(scopeStartFile, OffsetDateTime.now().minusYears(1).toString());
		managedScopeGate = new ManagedScopeGate(scopeStartFile.toString());
		haltSwitch = new TradingHaltSwitch(tempDir.resolve("STOP").toString(), fakeNotifier);

		when(accountService.getPrimaryAccountSeq()).thenReturn(ACCOUNT);
		when(orderPlacementService.placeMarketSell(anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(new OrderPlacementResult(OrderPlacementResult.Status.SIMULATED, null, null, null));
	}

	private HoldingTimeExitService newService() {
		return new HoldingTimeExitService(accountService, holdingsService, orderService, orderPlacementService,
			autoSellGuard, managedScopeGate, haltSwitch, fakeNotifier, "100000", "100000");
	}

	private static HoldingItem holding(String currency) {
		return new HoldingItem(SYMBOL, "테스트", "US".equals(currency) ? "US" : "KR", currency, "10", "100", "90",
			null, null, null, null);
	}

	private static Order buyOrder(OffsetDateTime filledAt) {
		OrderExecution execution = new OrderExecution("10", "90", "900", "0", "0", filledAt.toString(), null);
		return new Order("o1", SYMBOL, "BUY", "MARKET", "DAY", "CLOSED", null, "10", "900", "KRW",
			filledAt.toString(), null, execution);
	}

	@Test
	void overseasHoldingPastLimitOnlyNotifiesAndDoesNotSell() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("USD"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-overseas-" + SYMBOL));
		verify(orderPlacementService, never()).placeMarketSell(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void domesticHoldingPastLimitStillAttemptsAutoSell() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		verify(orderPlacementService).placeMarketSell(eq(ACCOUNT), anyString(), eq(SYMBOL), eq("10"));
	}
}
