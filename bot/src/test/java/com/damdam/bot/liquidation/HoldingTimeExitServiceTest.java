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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
	private final List<String> alertMessages = new ArrayList<>();
	private final Notifier fakeNotifier = (key, message) -> {
		alerts.add(key);
		alertMessages.add(message);
	};

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
		haltSwitch = new TradingHaltSwitch(tempDir.resolve("STOP").toString(), fakeNotifier, event -> {});

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

	@Test
	void missingEntryTimeNotifies() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of());
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-noentry-" + SYMBOL));
		verify(orderPlacementService, never()).placeMarketSell(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void outOfManagedScopeNotifies(@TempDir Path tempDir) throws IOException {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		// 관리 범위 시작 시각을 매수 체결일보다 나중으로 잡아서, 이 종목이 범위 밖(기능을 켜기 전부터 보유)이 되게 한다
		Path scopeStartFile = tempDir.resolve("scope-start-after-entry");
		Files.writeString(scopeStartFile, OffsetDateTime.now().toString());
		managedScopeGate = new ManagedScopeGate(scopeStartFile.toString());
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-scope-" + SYMBOL));
		verify(orderPlacementService, never()).placeMarketSell(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void orderValueOverLimitNotifies() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		// 한도를 매우 작게 잡아서(1원) 예상 주문 금액(10주 * 90원 = 900원)이 항상 한도를 넘게 한다
		HoldingTimeExitService service = new HoldingTimeExitService(accountService, holdingsService, orderService,
			orderPlacementService, autoSellGuard, managedScopeGate, haltSwitch, fakeNotifier, "1", "1");

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-limit-" + SYMBOL));
		verify(orderPlacementService, never()).placeMarketSell(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void autoSellFailureNotifies() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		when(orderPlacementService.placeMarketSell(anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(new OrderPlacementResult(OrderPlacementResult.Status.FAILED, null, null, "잔고 부족"));
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-failed-" + SYMBOL));
	}

	@Test
	void autoSellPlacedNotifies() {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		when(orderPlacementService.placeMarketSell(anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(new OrderPlacementResult(OrderPlacementResult.Status.PLACED, "order-1", null, null));
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertTrue(alerts.contains("time-exit-placed-" + SYMBOL));
	}

	// 하루 횟수는 폭주 방지가 목적이라 체결을 기다리지 않고 접수 시점에 바로 센다
	@Test
	void dailyAttemptIsRecordedRightAwayEvenBeforeFill(@TempDir Path tempDir) {
		autoSellGuard = new AutoSellGuard(1, 10, tempDir.resolve("guard.json").toString(),
			java.time.Clock.systemDefaultZone(), fakeNotifier);
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		when(orderPlacementService.placeMarketSell(anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(new OrderPlacementResult(OrderPlacementResult.Status.PLACED, "order-1", null, null));
		HoldingTimeExitService service = newService();

		service.checkAndAlert();

		assertFalse(autoSellGuard.canPlaceAutoSell(SYMBOL));
	}

	// lastPrice(100)만 보면 평단가(90)보다 높아 이익처럼 보이지만, 실제 체결금액에서 수수료/세금을 빼면 원가(900원)에 못 미쳐 손실이다
	@Test
	void onAutoSellFilledCountsAsLossWhenFeesTurnAWinIntoALoss(@TempDir Path tempDir) {
		autoSellGuard = new AutoSellGuard(10, 1, tempDir.resolve("guard.json").toString(),
			java.time.Clock.systemDefaultZone(), fakeNotifier);
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null,
			List.of(holding("KRW"))));
		when(orderService.getClosedOrders(anyLong(), anyString(), anyInt())).thenReturn(List.of(buyOrder(OLD_ENTRY)));
		when(orderPlacementService.placeMarketSell(anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(new OrderPlacementResult(OrderPlacementResult.Status.PLACED, "order-1", null, null));
		HoldingTimeExitService service = newService();
		service.checkAndAlert();
		assertTrue(autoSellGuard.canPlaceAutoSell(SYMBOL));

		service.onAutoSellFilled("order-1", new BigDecimal("10"), new BigDecimal("900"), new BigDecimal("1"), new BigDecimal("2"));

		assertFalse(autoSellGuard.canPlaceAutoSell(SYMBOL));
	}

	// OCO 트리거나 수동 매도처럼 시간 청산이 내지 않은 매도 체결은 연속 손실 판정에 영향을 주지 않는다
	@Test
	void onAutoSellFilledIgnoresOrdersItDidNotPlace(@TempDir Path tempDir) {
		autoSellGuard = new AutoSellGuard(10, 1, tempDir.resolve("guard.json").toString(),
			java.time.Clock.systemDefaultZone(), fakeNotifier);
		HoldingTimeExitService service = newService();

		service.onAutoSellFilled("unrelated-order", new BigDecimal("1"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"));

		assertTrue(autoSellGuard.canPlaceAutoSell(SYMBOL));
	}
}
