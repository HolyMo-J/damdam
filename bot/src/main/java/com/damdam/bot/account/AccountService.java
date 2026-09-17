package com.damdam.bot.account;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class AccountService {

	private final RestClient restClient;
	private final TokenService tokenService;

	// 계좌 목록은 실행 중에 바뀌지 않으므로, 같은 프로세스 안에서는 한 번만 조회해 재사용한다 (ACCOUNT 호출 제한 절약)
	private List<Account> cachedAccounts;

	public AccountService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	public synchronized List<Account> getAccounts() {
		if (cachedAccounts != null) {
			return cachedAccounts;
		}
		AccountsResponse response = restClient.get()
			.uri("/api/v1/accounts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(AccountsResponse.class);

		cachedAccounts = response == null ? List.of() : response.result();
		return cachedAccounts;
	}

	// 계좌가 여러 개면 주식 매매용(BROKERAGE) 계좌를, 없으면 첫 번째 계좌를 사용한다
	public long getPrimaryAccountSeq() {
		List<Account> accounts = getAccounts();
		if (accounts.isEmpty()) {
			throw new IllegalStateException("조회된 계좌가 없습니다.");
		}
		return accounts.stream()
			.filter(account -> "BROKERAGE".equals(account.accountType()))
			.findFirst()
			.orElse(accounts.get(0))
			.accountSeq();
	}
}
