package com.damdam.bot.ranking;

import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class RankingServiceTest {

	private static final String ONE_RANKING = """
		{"result":{"rankedAt":"2026-09-29T15:35:00+09:00","rankings":[
		 {"rank":1,"symbol":"A","currency":"KRW","price":{"lastPrice":"100","basePrice":"90","changeRate":"0.11"},
		  "tradingVolume":"10","tradingAmount":"1000"}]}}""";

	private MockRestServiceServer server;
	private RankingService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://test");
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("dummy-token");
		service = new RankingService(builder.build(), tokenService);
	}

	@Test
	void pageMethodSendsTheChosenFlagAndKeepsRankedAt() {
		server.expect(requestTo(startsWith("http://test/api/v1/rankings")))
			.andExpect(queryParam("type", "MARKET_TRADING_AMOUNT"))
			.andExpect(queryParam("marketCountry", "KR"))
			.andExpect(queryParam("duration", "1d"))
			.andExpect(queryParam("excludeInvestmentCaution", "false"))
			.andExpect(queryParam("count", "100"))
			.andRespond(withSuccess(ONE_RANKING, MediaType.APPLICATION_JSON));

		RankingPage page = service.getMarketTradingAmountPage("KR", "1d", 100, false);

		assertEquals("2026-09-29T15:35:00+09:00", page.rankedAt());
		assertEquals(1, page.rankings().size());
		assertEquals("A", page.rankings().get(0).symbol());
		server.verify();
	}

	@Test
	void existingTopMethodStillExcludesInvestmentCaution() {
		// 대상 종목군 계산이 쓰는 기존 메서드의 동작(옵션 켬)은 그대로여야 한다
		server.expect(requestTo(startsWith("http://test/api/v1/rankings")))
			.andExpect(queryParam("excludeInvestmentCaution", "true"))
			.andRespond(withSuccess(ONE_RANKING, MediaType.APPLICATION_JSON));

		List<Ranking> rankings = service.getMarketTradingAmountTop("KR", "1d", 100);

		assertEquals(List.of("A"), rankings.stream().map(Ranking::symbol).toList());
		server.verify();
	}

	@Test
	void noAggregationMeansEmptyRankingsAndNullRankedAt() {
		server.expect(requestTo(startsWith("http://test/api/v1/rankings")))
			.andRespond(withSuccess("{\"result\":{\"rankedAt\":null,\"rankings\":[]}}", MediaType.APPLICATION_JSON));

		RankingPage page = service.getMarketTradingAmountPage("KR", "1d", 100, true);

		assertNull(page.rankedAt());
		assertEquals(List.of(), page.rankings());
	}

	@Test
	void emptyBodyGivesAnEmptyPageInsteadOfFailing() {
		server.expect(requestTo(startsWith("http://test/api/v1/rankings"))).andRespond(withSuccess());
		RankingPage page = service.getMarketTradingAmountPage("KR", "1d", 100, true);
		assertEquals(List.of(), page.rankings());
	}
}
