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
		return getMarketTradingAmountPage(marketCountry, duration, count, true).rankings();
	}

	// 집계 기준 시각(rankedAt)까지 담아 돌려주고, 투자유의 종목 제외 여부를 고를 수 있다.
	// 이 옵션이 정확히 어떤 지정 종목까지 빼는지는 명세에 설명이 없어서, 조회 러너가 켠 결과와 끈 결과를 비교하는 데 쓴다
	public RankingPage getMarketTradingAmountPage(String marketCountry, String duration, int count, boolean excludeInvestmentCaution) {
		String uri = UriComponentsBuilder.fromPath("/api/v1/rankings")
			.queryParam("type", "MARKET_TRADING_AMOUNT")
			.queryParam("marketCountry", marketCountry)
			.queryParam("duration", duration)
			.queryParam("excludeInvestmentCaution", excludeInvestmentCaution)
			.queryParam("count", count)
			.toUriString();

		RankingsResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(RankingsResponse.class);

		return response == null ? new RankingPage(null, List.of()) : response.result();
	}
}
