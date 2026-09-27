package com.damdam.bot.conditionalorder;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.control.TradingResumedEvent;
import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.orderevent.OrderResyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcoPeriodicCheckServiceTest {

	private static final long ACCOUNT = 1L;
	// 2026-09-28(월) 10:00 KST. 장중 판정의 기준 시각으로 쓴다 (실제 개장일 여부는 marketCalendarService를 모킹해서 정한다)
	private static final Clock DURING_MARKET_HOURS = Clock.fixed(Instant.parse("2026-09-28T01:00:00Z"), ZoneId.of("Asia/Seoul"));
	private static final Clock AFTER_MARKET_HOURS = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneId.of("Asia/Seoul"));

	private OrderResyncService orderResyncService;
	private MarketCalendarService marketCalendarService;
	private final List<String> alerts = new ArrayList<>();
	private OcoPeriodicCheckService service;

	@BeforeEach
	void setUp() {
		AccountService accountService = mock(AccountService.class);
		orderResyncService = mock(OrderResyncService.class);
		marketCalendarService = mock(MarketCalendarService.class);
		when(accountService.getPrimaryAccountSeq()).thenReturn(ACCOUNT);
		when(marketCalendarService.isTradingDay(any())).thenReturn(true);
		service = new OcoPeriodicCheckService(accountService, orderResyncService, marketCalendarService,
			DURING_MARKET_HOURS, (key, message) -> alerts.add(key));
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

	// 관리 대상은 국내 종목뿐이고 OCO는 KRX 정규장(09:00~15:30)에서만 발동되므로, 장 시간이 아니면 API를 부르지 않는다
	@Test
	void skipsTheCheckOutsideMarketHours() {
		AccountService accountService = mock(AccountService.class);
		OcoPeriodicCheckService afterHours = new OcoPeriodicCheckService(accountService, orderResyncService,
			marketCalendarService, AFTER_MARKET_HOURS, (key, message) -> alerts.add(key));

		afterHours.checkPeriodically();

		verify(orderResyncService, never()).ensureOcosForManagedHoldings(anyLong());
		verify(accountService, never()).getPrimaryAccountSeq();
	}

	// 장 시간 안이어도 휴장일(주말/공휴일)이면 건너뛴다
	@Test
	void skipsTheCheckOnNonTradingDays() {
		when(marketCalendarService.isTradingDay(any())).thenReturn(false);

		service.checkPeriodically();

		verify(orderResyncService, never()).ensureOcosForManagedHoldings(anyLong());
	}
}
