package com.damdam.bot.commission;

import com.damdam.bot.account.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// query 프로필에서 실제 계좌의 시장별 매매 수수료율을 조회만 한다 (2단계 백테스트에서 비용 반영용)
@Component
@Profile("query")
@Order(6)
public class CommissionQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(CommissionQueryRunner.class);

	private final AccountService accountService;
	private final CommissionService commissionService;

	public CommissionQueryRunner(AccountService accountService, CommissionService commissionService) {
		this.accountService = accountService;
		this.commissionService = commissionService;
	}

	@Override
	public void run(String... args) {
		long accountSeq = accountService.getPrimaryAccountSeq();
		for (Commission commission : commissionService.getCommissions(accountSeq)) {
			log.info("[수수료] {} 수수료율 {} (적용기간: {} ~ {})",
				commission.marketCountry(), commission.commissionRate(),
				commission.startDate() == null ? "제한없음" : commission.startDate(),
				commission.endDate() == null ? "무기한" : commission.endDate());
		}
	}
}
