package com.damdam.bot.conditionalorder;

import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsOverview;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.market.AtrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 협력 객체를 모두 가짜로 바꿔서, 실제 API 호출 없이 "매도 체결 뒤에만 OCO를 정리한다"는 규칙을 검증한다
class AtrOcoManagementServiceTest {

	private static final long ACCOUNT = 1L;
	private static final String SYMBOL = "AAA";

	private HoldingsService holdingsService;
	private AtrService atrService;
	private ConditionalOrderService conditionalOrderService;
	private AtrOcoManagementService service;

	@BeforeEach
	void setUp() {
		holdingsService = mock(HoldingsService.class);
		atrService = mock(AtrService.class);
		conditionalOrderService = mock(ConditionalOrderService.class);
		service = new AtrOcoManagementService(holdingsService, atrService, conditionalOrderService);
	}

	private static HoldingItem holding(String quantity) {
		return new HoldingItem(SYMBOL, "테스트", "US", "USD", quantity, "100", "100", null, null, null, null);
	}

	private void givenHoldings(HoldingItem... items) {
		when(holdingsService.getHoldings(ACCOUNT)).thenReturn(new HoldingsOverview(null, null, null, null, List.of(items)));
	}

	private static ConditionalOrderDetail openOco() {
		return new ConditionalOrderDetail("oco-1", "OCO", "OPEN", SYMBOL, "US", "5", "LIMIT", "2026-10-01", null, null, null);
	}

	@Test
	void cancelsLeftoverOcoWhenPositionIsFullySold() {
		givenHoldings();
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco()));

		service.syncAfterSellFill(ACCOUNT, SYMBOL);

		verify(conditionalOrderService).cancelConditionalOrder(ACCOUNT, "oco-1");
	}

	@Test
	void treatsZeroQuantityHoldingAsSold() {
		givenHoldings(holding("0"));
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco()));

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
		when(conditionalOrderService.findOpenConditionalOrder(ACCOUNT, SYMBOL)).thenReturn(Optional.of(openOco()));
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
}
