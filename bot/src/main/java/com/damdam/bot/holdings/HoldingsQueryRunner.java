package com.damdam.bot.holdings;

import com.damdam.bot.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

// query 프로필로 실행할 때만 동작. 기본 실행이나 테스트에서 실제 API를 자동으로 호출하지 않기 위함
@Component
@Profile("query")
@Order(2)
public class HoldingsQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(HoldingsQueryRunner.class);

	private final AccountService accountService;
	private final HoldingsService holdingsService;

	public HoldingsQueryRunner(AccountService accountService, HoldingsService holdingsService) {
		this.accountService = accountService;
		this.holdingsService = holdingsService;
	}

	@Override
	public void run(String... args) {
		long accountSeq = accountService.getPrimaryAccountSeq();
		HoldingsOverview overview = holdingsService.getHoldings(accountSeq);

		if (overview.items().isEmpty()) {
			log.info("보유 종목이 없습니다.");
			return;
		}

		log.info("평가금액: {}원, 손익: {}원 ({}%)",
			overview.marketValue().amount().krw(),
			overview.profitLoss().amount().krw(),
			toPercent(overview.profitLoss().rate()));

		for (HoldingItem item : overview.items()) {
			log.info("{} ({}), 수량: {}, 평단가: {}, 현재가: {}, 손익률: {}%",
				item.name(), item.symbol(), item.quantity(),
				item.averagePurchasePrice(), item.lastPrice(),
				toPercent(item.profitLoss().rate()));
		}
	}

	private String toPercent(String rate) {
		if (rate == null) {
			return "0";
		}
		return new BigDecimal(rate)
			.multiply(BigDecimal.valueOf(100))
			.setScale(2, RoundingMode.HALF_UP)
			.toPlainString();
	}
}
