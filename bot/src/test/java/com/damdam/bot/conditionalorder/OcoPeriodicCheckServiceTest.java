package com.damdam.bot.conditionalorder;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.control.TradingResumedEvent;
import com.damdam.bot.orderevent.OrderResyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcoPeriodicCheckServiceTest {

	private static final long ACCOUNT = 1L;

	private OrderResyncService orderResyncService;
	private final List<String> alerts = new ArrayList<>();
	private OcoPeriodicCheckService service;

	@BeforeEach
	void setUp() {
		AccountService accountService = mock(AccountService.class);
		orderResyncService = mock(OrderResyncService.class);
		when(accountService.getPrimaryAccountSeq()).thenReturn(ACCOUNT);
		service = new OcoPeriodicCheckService(accountService, orderResyncService, (key, message) -> alerts.add(key));
	}

	@Test
	void checksManagedHoldingsOnEachTick() {
		when(orderResyncService.ensureOcosForManagedHoldings(ACCOUNT)).thenReturn(0);

		service.checkPeriodically();

		verify(orderResyncService).ensureOcosForManagedHoldings(ACCOUNT);
		assertTrue(alerts.isEmpty());
	}

	// 정기 점검에서 실제로 보정한 게 있으면 알린다. 평소엔 재연결 때만 필요한 보정이라 이례적인 신호이기 때문이다
	@Test
	void notifiesWhenAFixWasNeeded() {
		when(orderResyncService.ensureOcosForManagedHoldings(ACCOUNT)).thenReturn(2);

		service.checkPeriodically();

		assertEquals(List.of("oco-periodic-fixed"), alerts);
	}

	// 정지 파일이 사라지는 순간에도 같은 점검이 즉시 돈다
	@Test
	void alsoChecksImmediatelyWhenTradingResumes() {
		when(orderResyncService.ensureOcosForManagedHoldings(ACCOUNT)).thenReturn(0);

		service.onTradingResumed(new TradingResumedEvent());

		verify(orderResyncService).ensureOcosForManagedHoldings(ACCOUNT);
	}
}
