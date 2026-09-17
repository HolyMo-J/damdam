package com.damdam.bot.orderevent;

import com.damdam.bot.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	public OrderStreamRunner(AccountService accountService, OrderStreamClient orderStreamClient) {
		this.accountService = accountService;
		this.orderStreamClient = orderStreamClient;
	}

	@Override
	public void run(String... args) throws InterruptedException {
		long accountSeq = accountService.getPrimaryAccountSeq();
		log.info("계좌 {} 주문 이벤트 수신을 시작합니다. 종료하려면 Ctrl+C.", accountSeq);

		CountDownLatch shutdownLatch = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			orderStreamClient.stop();
			shutdownLatch.countDown();
		}));

		orderStreamClient.start(accountSeq);
		shutdownLatch.await();
	}
}
