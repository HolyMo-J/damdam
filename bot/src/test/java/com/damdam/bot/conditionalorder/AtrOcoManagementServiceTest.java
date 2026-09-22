package com.damdam.bot.conditionalorder;

import com.damdam.bot.control.TradingHaltSwitch;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsOverview;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.market.AtrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 협력 객체를 모두 가짜로 바꿔서, "매도 체결 뒤에만 OCO를 정리한다"와 "해외 종목은 OCO 관리 대상이 아니다"는 규칙을 검증한다
class AtrOcoManagementServiceTest {

	private static final long ACCOUNT = 1L;
	private static final String SYMBOL = "AAA";

	private HoldingsService holdingsService;
	private AtrService atrService;
	private ConditionalOrderService conditionalOrderService;
	private AtrOcoManagementService service;
	private final List<String> alerts = new ArrayList<>();

	@BeforeEach
	void setUp() {
		holdingsService = mock(HoldingsService.class);
		atrService = mock(AtrService.class);
		conditionalOrderService = mock(ConditionalOrderService.class);
		service = newService(new TradingHaltSwitch("build/tmp/test-no-halt-file", (key, message) -> {}, event -> {}));
	}

	private AtrOcoManagementService newService(TradingHaltSwitch haltSwitch) {
		return new AtrOcoManagementService(holdingsService, atrService, conditionalOrderService,
			(key, message) -> alerts.add(key), haltSwitch);
	}

	// 관리 대상인 국내 종목을 기본값으로 쓴다. 해외는 별도 테스트에서 다룬다
	private static HoldingItem holding(String quantity) {
		return new HoldingItem(SYMBOL, "테스트", "KR", "KRW", quantity, "100", "100", null, null, null, null);
	}

	private static HoldingItem overseasHolding(String quantity) {
		return new HoldingItem(SYMBOL, "테스트", "US", "USD", quantity, "100", "100", null, null, null, null);
	}

	private void givenHoldings(HoldingItem... items) {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null, List.of(items)));
	}

	private static ConditionalOrderDetail openOco(String market) {
		return new ConditionalOrderDetail("oco-1", "OCO", "OPEN", SYMBOL, market, "5", "LIMIT", "2026-10-01", null, null, null);
	}

	@Test
	void cancelsLeftoverOcoWhenPositionIsFullySold() {
		givenHoldings();
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco("KR")));
		when(conditionalOrderService.cancelConditionalOrder(ACCOUNT, "oco-1")).thenReturn(true);

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService).cancelConditionalOrder(ACCOUNT, "oco-1");
	}

	@Test
	void treatsZeroQuantityHoldingAsSold() {
		givenHoldings(holding("0"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco("KR")));
		when(conditionalOrderService.cancelConditionalOrder(ACCOUNT, "oco-1")).thenReturn(true);

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService).cancelConditionalOrder(ACCOUNT, "oco-1");
	}

	@Test
	void doesNothingWhenNoOcoIsOpen() {
		givenHoldings();
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).cancelConditionalOrder(eq(ACCOUNT), anyString());
	}

	// 일부만 팔렸으면 취소하지 않고, 이미 있는 OCO의 수량만 남은 보유량에 맞춘다
	@Test
	void shrinksOcoQuantityWhenPartOfThePositionRemains() {
		givenHoldings(holding("3"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco("KR")));
		when(atrService.getAtr14(SYMBOL)).thenReturn(new BigDecimal("2"));

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).cancelConditionalOrder(eq(ACCOUNT), anyString());
		verify(conditionalOrderService).modifyAtrOco(eq(ACCOUNT), eq("oco-1"), eq("3"), anyString(),
			anyString(), anyString(), anyString(), anyString());
	}

	// 남은 보유가 있는데 OCO가 없는 경우(관리 범위 밖일 수 있음)에는 새로 만들지 않는다
	@Test
	void doesNotCreateAnOcoForARemainingPositionWithoutOne() {
		givenHoldings(holding("3"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString());
	}

	// 조회 중 오류가 나도 예외를 밖으로 던지지 않는다 (웹소켓 처리를 막지 않기 위해)
	@Test
	void swallowsErrorsSoTheWebSocketLoopKeepsRunning() {
		when(holdingsService.getHoldings(ACCOUNT)).thenThrow(new IllegalStateException("보유 조회 실패"));

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).cancelConditionalOrder(eq(ACCOUNT), any());
	}

	@Test
	void alertsWhenRegisteringTheOcoFails() {
		givenHoldings(holding("5"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());
		when(atrService.getAtr14(SYMBOL)).thenReturn(new BigDecimal("2"));
		when(conditionalOrderService.createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString()))
			.thenReturn(new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.FAILED, null, "400 호가 단위 불일치"));

		service.syncAfterBuyFill(ACCOUNT, SYMBOL);

		assertEquals(List.of("oco-" + SYMBOL), alerts);
	}

	@Test
	void doesNotAlertWhenRegistrationIsSimulated() {
		givenHoldings(holding("5"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());
		when(atrService.getAtr14(SYMBOL)).thenReturn(new BigDecimal("2"));
		when(conditionalOrderService.createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString()))
			.thenReturn(new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.SIMULATED, null, null));

		service.syncAfterBuyFill(ACCOUNT, SYMBOL);

		assertTrue(alerts.isEmpty());
	}

	// 정지 파일이 있으면 OCO를 등록/수정하지 않고 알린다. 하지만 남은 OCO를 취소하는 것은 막지 않는다
	@Test
	void haltFileStopsRegistrationButNotCancellation(@TempDir Path tempDir) throws IOException {
		Path haltFile = tempDir.resolve("STOP");
		Files.writeString(haltFile, "");
		AtrOcoManagementService halted = newService(new TradingHaltSwitch(haltFile.toString(), (key, message) -> {}, event -> {}));
		givenHoldings(holding("5"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());

		halted.syncAfterBuyFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString());
		assertEquals(List.of("oco-" + SYMBOL), alerts);

		givenHoldings();
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco("KR")));
		when(conditionalOrderService.cancelConditionalOrder(ACCOUNT, "oco-1")).thenReturn(true);
		halted.syncAfterSellFill(ACCOUNT, SYMBOL);
		verify(conditionalOrderService).cancelConditionalOrder(ACCOUNT, "oco-1");
	}

	// 해외 종목은 시간 청산처럼 OCO도 관리 대상이 아니다 (첫 실전은 국내 종목만, docs/live-checklist.md)
	@Test
	void doesNotCreateOrModifyOcoForOverseasHoldings() {
		givenHoldings(overseasHolding("5"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.empty());

		service.syncAfterBuyFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString());
		assertEquals(List.of("oco-overseas-" + SYMBOL), alerts);
	}

	@Test
	void leavesExistingOverseasOcoAloneWhenPositionIsFullySold() {
		givenHoldings();
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco("US")));

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService, never()).cancelConditionalOrder(eq(ACCOUNT), anyString());
		assertEquals(List.of("oco-overseas-leftover-" + SYMBOL), alerts);
	}

	@Test
	void ensureOcoDoesNothingForOverseasHoldings() {
		givenHoldings(overseasHolding("5"));

		boolean touched = service.ensureOco(ACCOUNT, SYMBOL);

		assertFalse(touched);
		verify(conditionalOrderService, never()).createAtrOco(anyLong(), anyString(), anyString(), anyString(), anyString(),
			anyString(), anyString(), anyString(), anyString());
	}
}
