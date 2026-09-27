package com.damdam.bot.conditionalorder;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.control.TradingResumedEvent;
import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.orderevent.OrderResyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

// 웹소켓 재연결 사이(구독 유지 중)에도 관리 범위 보유 종목의 OCO를 정기적으로 점검한다.
// OCO 등록 실패나 매도 체결 직후 보유 조회 지연이 있으면, 지금까지는 다음 재연결(OrderResyncService.resync)
// 전까지 손절 보호가 없을 수 있었다. 관리 종목이 소수(단타 위주)라 종목별 순차 호출로도
// 초당 호출 제한(조건주문 조회 10회, 등록/수정 5회, 보유조회 5회, overview.md 기준)에 여유가 크다
// 관리 대상은 국내 종목뿐이고 OCO는 KRX 정규장에서만 발동되므로, 장 시간이 아니면 점검을 건너뛴다
// (docs/todo.md "장 운영시간 가드" 항목, 2026-09-28). 서버가 어느 시간대에서 돌든 KST 기준으로 판단해야 해서
// 시스템 기본 시간대 대신 Asia/Seoul을 명시한다
@Component
@Profile("listen")
public class OcoPeriodicCheckService {

	private static final Logger log = LoggerFactory.getLogger(OcoPeriodicCheckService.class);
	private static final LocalTime MARKET_OPEN = LocalTime.of(9, 0);
	private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

	private final AccountService accountService;
	private final OrderResyncService orderResyncService;
	private final MarketCalendarService marketCalendarService;
	private final Clock clock;
	private final Notifier notifier;

	@Autowired
	public OcoPeriodicCheckService(AccountService accountService, OrderResyncService orderResyncService,
			MarketCalendarService marketCalendarService, Notifier notifier) {
		this(accountService, orderResyncService, marketCalendarService, Clock.system(ZoneId.of("Asia/Seoul")), notifier);
	}

	OcoPeriodicCheckService(AccountService accountService, OrderResyncService orderResyncService,
			MarketCalendarService marketCalendarService, Clock clock, Notifier notifier) {
		this.accountService = accountService;
		this.orderResyncService = orderResyncService;
		this.marketCalendarService = marketCalendarService;
		this.clock = clock;
		this.notifier = notifier;
	}

	@Scheduled(fixedDelay = 60000)
	public void checkPeriodically() {
		check();
	}

	// 정지 파일이 사라지는 순간 바로 한 번 점검한다 (재개 감지 자체는 최대 5초 지연, ControlFileWatcher 참고)
	@EventListener(TradingResumedEvent.class)
	public void onTradingResumed(TradingResumedEvent event) {
		check();
	}

	private void check() {
		if (!isKrxRegularSessionNow()) {
			return;
		}
		long accountSeq = accountService.getPrimaryAccountSeq();
		int fixed = orderResyncService.ensureOcosForManagedHoldings(accountSeq);
		if (fixed > 0) {
			log.info("[OCO 정기 점검] {}건 보정", fixed);
			notifier.send("oco-periodic-fixed", "[담담] 정기 점검에서 OCO " + fixed
				+ "건을 보정했습니다. 재연결 전까지 손절 보호가 비어 있었을 수 있으니 원인을 확인하세요.");
		}
	}

	private boolean isKrxRegularSessionNow() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalTime time = now.toLocalTime();
		if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
			return false;
		}
		return marketCalendarService.isTradingDay(now.toLocalDate());
	}
}
