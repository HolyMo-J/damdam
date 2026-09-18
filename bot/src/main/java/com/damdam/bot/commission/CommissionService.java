package com.damdam.bot.commission;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class CommissionService {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TokenService tokenService;

	public CommissionService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	public List<Commission> getCommissions(long accountSeq) {
		CommissionsResponse response = restClient.get()
			.uri("/api/v1/commissions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
			.retrieve()
			.body(CommissionsResponse.class);

		return response == null ? List.of() : response.result();
	}
}
