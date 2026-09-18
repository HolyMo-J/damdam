package com.damdam.bot.market;

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

// query 프로필에서 보유 종목별 14일 ATR을 계산해 출력한다. 조회만 하고 주문은 하지 않는다
@Component
@Profile("query")
@Order(4)
public class AtrQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(AtrQueryRunner.class);

	private final AccountService accountService;
	private final HoldingsService holdingsService;
	private final AtrService atrService;

	public AtrQueryRunner(AccountService accountService, HoldingsService holdingsService, AtrService atrService) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
		this.atrService = atrService;
	}

	@Override
	public void run(String... args) {
		long accountSeq = accountService.getPrimaryAccountSeq();
		for (HoldingItem item : holdingsService.getHoldings(accountSeq).items()) {
			if (new BigDecimal(item.quantity()).signum() <= 0) {
				continue;
			}
			try {
				BigDecimal atr14 = atrService.getAtr14(item.symbol());
				BigDecimal lastPrice = new BigDecimal(item.lastPrice());
				log.info("[ATR] {} 현재가 {} {}, 14일 ATR {} (익절 목표 {}, 손절 기준 {})",
					item.symbol(), lastPrice, item.currency(), atr14,
					lastPrice.add(atr14), lastPrice.subtract(atr14));
			} catch (Exception e) {
				log.warn("[ATR] {} 계산 실패: {}", item.symbol(), e.getMessage());
			}
		}
	}
}
