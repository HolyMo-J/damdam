package com.damdam.bot.market;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
public class MarketDataService {

	private final RestClient restClient;
	private final TokenService tokenService;

	public MarketDataService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// 최신순(timestamp 내림차순)으로 반환한다. count는 최대 200
	public List<Candle> getDailyCandles(String symbol, int count) {
		String uri = UriComponentsBuilder.fromPath("/api/v1/candles")
			.queryParam("symbol", symbol)
			.queryParam("interval", "1d")
			.queryParam("count", count)
			.toUriString();

		CandlesResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(CandlesResponse.class);

		return response == null ? List.of() : response.result().candles();
	}
}
