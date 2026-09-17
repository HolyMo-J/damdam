package com.damdam.bot.holdings;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class HoldingsService {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TokenService tokenService;

	public HoldingsService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	public HoldingsOverview getHoldings(long accountSeq) {
		HoldingsResponse response = restClient.get()
			.uri("/api/v1/holdings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
			.retrieve()
			.body(HoldingsResponse.class);

		if (response == null) {
			throw new IllegalStateException("보유 종목 응답이 비어 있습니다.");
		}
		return response.result();
	}
}
