package com.damdam.bot.orderevent;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.notification.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;

// listen 프로필로 실행할 때만 동작. Ctrl+C로 종료할 때까지 계속 주문 이벤트를 수신한다
@Component
@Profile("listen")
public class OrderStreamRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(OrderStreamRunner.class);

	private final AccountService accountService;
	private final OrderStreamClient orderStreamClient;
	private final Notifier notifier;
	private final boolean liveMode;

	public OrderStreamRunner(AccountService accountService, OrderStreamClient orderStreamClient, Notifier notifier,
			@Value("${damdam.orders.live-mode}") boolean liveMode) {
		this.accountService = accountService;
		this.orderStreamClient = orderStreamClient;
		this.notifier = notifier;
		this.liveMode = liveMode;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		long accountSeq = accountService.getPrimaryAccountSeq();
		log.info("계좌 {} 주문 이벤트 수신을 시작합니다. 종료하려면 Ctrl+C.", accountSeq);

		CountDownLatch shutdownLatch = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// 종료 중에는 비동기 전송이 끝나기 전에 프로세스가 끝날 수 있어서 동기로 보낸다
			notifier.sendNow("[담담] 봇이 종료됩니다.");
			orderStreamClient.stop();
			shutdownLatch.countDown();
		}));

		orderStreamClient.start(accountSeq);
		notifier.send("bot-start", "[담담] 봇이 시작됐습니다. 모드: " + (liveMode ? "실전(실제 주문 전송)" : "모의 실행"));
		shutdownLatch.await();
	}
}
