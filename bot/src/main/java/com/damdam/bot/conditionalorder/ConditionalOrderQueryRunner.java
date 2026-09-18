package com.damdam.bot.conditionalorder;

import com.damdam.bot.account.AccountService;
import com.damdam.bot.holdings.HoldingItem;
import com.damdam.bot.holdings.HoldingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

// query 프로필에서 보유 종목별로 진행 중인 조건주문이 있는지 조회만 한다 (등록/수정/취소는 하지 않음)
@Component
@Profile("query")
@Order(5)
public class ConditionalOrderQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(ConditionalOrderQueryRunner.class);

	private final AccountService accountService;
	private final HoldingsService holdingsService;
	private final ConditionalOrderService conditionalOrderService;

	public ConditionalOrderQueryRunner(AccountService accountService, HoldingsService holdingsService,
			ConditionalOrderService conditionalOrderService) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
		this.conditionalOrderService = conditionalOrderService;
	}

	@Override
	public void run(String... args) {
		long accountSeq = accountService.getPrimaryAccountSeq();
		for (HoldingItem item : holdingsService.getHoldings(accountSeq).items()) {
			if (new BigDecimal(item.quantity()).signum() <= 0) {
				continue;
			}
			Optional<ConditionalOrderDetail> existing = conditionalOrderService.findOpenConditionalOrder(accountSeq, item.symbol());
			if (existing.isPresent()) {
				ConditionalOrderDetail detail = existing.get();
				log.info("[조건주문 조회] {} 진행 중인 조건주문 있음: {} (id={})", item.symbol(), detail.type(), detail.conditionalOrderId());
			} else {
				log.info("[조건주문 조회] {} 진행 중인 조건주문 없음", item.symbol());
			}
		}
	}
}
