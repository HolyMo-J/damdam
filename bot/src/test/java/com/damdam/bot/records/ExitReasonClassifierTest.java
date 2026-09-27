package com.damdam.bot.records;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.conditionalorder.AtrOcoPricing;
import com.damdam.bot.liquidation.HoldingTimeExitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 웹소켓/REST 체결 이벤트에는 OCO leg 구분 필드가 없어서(docs/review-tasks.md 8번), 다른 서비스가 들고 있는
// 상태(시간 청산 대기 주문ID, 마지막 OCO 가격)로 청산 사유를 역으로 추정하는 규칙을 검증한다
class ExitReasonClassifierTest {

	private static final String SYMBOL = "AAA";
	private static final String ORDER_ID = "order-1";

	private HoldingTimeExitService holdingTimeExitService;
	private AtrOcoManagementService atrOcoManagementService;
	private ExitReasonClassifier classifier;

	@BeforeEach
	void setUp() {
		holdingTimeExitService = mock(HoldingTimeExitService.class);
		atrOcoManagementService = mock(AtrOcoManagementService.class);
		classifier = new ExitReasonClassifier(holdingTimeExitService, atrOcoManagementService);
	}

	@Test
	void classifiesAsTimeExitWhenTheOrderIsATimeExitSell() {
		when(holdingTimeExitService.isPendingAutoSell(ORDER_ID)).thenReturn(true);

		assertEquals(ExitReasonClassifier.TIME_EXIT, classifier.classifySell(ORDER_ID, SYMBOL, "1"));
	}

	@Test
	void classifiesAsManualWhenNoOcoPricesAreKnown() {
		when(atrOcoManagementService.lastKnownPrices(SYMBOL)).thenReturn(Optional.empty());

		assertEquals(ExitReasonClassifier.MANUAL, classifier.classifySell(ORDER_ID, SYMBOL, "100"));
	}

	@Test
	void classifiesAsTakeProfitWhenTheFillIsAtOrAboveTheTakeProfitTrigger() {
		givenOcoPrices("110", "90");

		assertEquals(ExitReasonClassifier.TAKE_PROFIT, classifier.classifySell(ORDER_ID, SYMBOL, "110"));
	}

	@Test
	void classifiesAsStopLossWhenTheFillIsAtOrBelowTheStopLossTrigger() {
		givenOcoPrices("110", "90");

		assertEquals(ExitReasonClassifier.STOP_LOSS, classifier.classifySell(ORDER_ID, SYMBOL, "90"));
	}

	// 익절가와 손절가 사이에서 체결됐다면 OCO 트리거가 아니라 사용자가 직접 판 것으로 본다
	@Test
	void classifiesAsManualWhenTheFillIsBetweenTheTriggers() {
		givenOcoPrices("110", "90");

		assertEquals(ExitReasonClassifier.MANUAL, classifier.classifySell(ORDER_ID, SYMBOL, "100"));
	}

	private void givenOcoPrices(String takeProfitTrigger, String stopLossTrigger) {
		when(holdingTimeExitService.isPendingAutoSell(anyString())).thenReturn(false);
		when(atrOcoManagementService.lastKnownPrices(SYMBOL))
			.thenReturn(Optional.of(new AtrOcoPricing.Prices(takeProfitTrigger, takeProfitTrigger, stopLossTrigger, stopLossTrigger)));
	}
}
