package com.damdam.bot.stocks;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
public class StockInfoService {

	private final RestClient restClient;
	private final TokenService tokenService;

	public StockInfoService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// symbols는 최대 200개까지 한 번에 조회 가능
	public List<StockInfo> getStocks(List<String> symbols) {
		String joined = String.join(",", symbols);

		StocksResponse response = restClient.get()
			.uri("/api/v1/stocks?symbols={symbols}", joined)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(StocksResponse.class);

		return response == null ? List.of() : response.result();
	}
}
