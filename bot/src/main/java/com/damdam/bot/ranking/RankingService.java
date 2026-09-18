package com.damdam.bot.ranking;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
public class RankingService {

	private final RestClient restClient;
	private final TokenService tokenService;

	public RankingService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// 계좌와 무관한 시세 랭킹. 투자유의 종목은 제외하고 조회한다 (복권형 종목 배제 목적)
	public List<Ranking> getMarketTradingAmountTop(String marketCountry, String duration, int count) {
		String uri = UriComponentsBuilder.fromPath("/api/v1/rankings")
			.queryParam("type", "MARKET_TRADING_AMOUNT")
			.queryParam("marketCountry", marketCountry)
			.queryParam("duration", duration)
			.queryParam("excludeInvestmentCaution", true)
			.queryParam("count", count)
			.toUriString();

		RankingsResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(RankingsResponse.class);

		return response == null ? List.of() : response.result().rankings();
	}
}
