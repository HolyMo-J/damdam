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

	public AccountService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	public List<Account> getAccounts() {
		AccountsResponse response = restClient.get()
			.uri("/api/v1/accounts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(AccountsResponse.class);

		return response == null ? List.of() : response.result();
	}
}
