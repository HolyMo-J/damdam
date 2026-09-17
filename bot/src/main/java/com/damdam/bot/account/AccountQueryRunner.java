package com.damdam.bot.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// query 프로필로 실행할 때만 동작. 기본 실행이나 테스트에서 실제 API를 자동으로 호출하지 않기 위함
@Component
@Profile("query")
@Order(1)
public class AccountQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(AccountQueryRunner.class);

	private final AccountService accountService;

	public AccountQueryRunner(AccountService accountService) {
		this.accountService = accountService;
	}

	@Override
	public void run(String... args) {
		var accounts = accountService.getAccounts();
		if (accounts.isEmpty()) {
			log.info("조회된 계좌가 없습니다.");
			return;
		}
		for (Account account : accounts) {
			log.info("계좌번호: {}, accountSeq: {}, 계좌유형: {}",
				mask(account.accountNo()), account.accountSeq(), account.accountType());
		}
	}

	private String mask(String accountNo) {
		if (accountNo == null || accountNo.length() <= 4) {
			return "****";
		}
		int visibleLength = 4;
		String masked = "*".repeat(accountNo.length() - visibleLength);
		return masked + accountNo.substring(accountNo.length() - visibleLength);
	}
}
