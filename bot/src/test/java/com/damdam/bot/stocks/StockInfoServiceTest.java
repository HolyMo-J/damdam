package com.damdam.bot.stocks;

import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 응답 JSON이 StockInfo로 어떻게 읽히는지 고정한다. 특히 필드가 빠졌을 때 "안전해 보이는 값"으로 조용히 채워지지 않는지 본다
class StockInfoServiceTest {

	private MockRestServiceServer server;
	private StockInfoService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://test");
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("dummy-token");
		service = new StockInfoService(builder.build(), tokenService);
	}

	private List<StockInfo> respond(String json) {
		server.expect(requestTo("http://test/api/v1/stocks?symbols=A")).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
		return service.getStocks(List.of("A"));
	}

	@Test
	void parsesAllFieldsIncludingKoreanMarketDetail() {
		List<StockInfo> result = respond("""
			{"result":[{"symbol":"A","name":"이름","englishName":"n","isinCode":"KR1","market":"KOSPI","securityType":"STOCK",
			"isCommonShare":true,"status":"ACTIVE","currency":"KRW","koreanMarketDetail":
			{"liquidationTrading":true,"nxtSupported":true,"krxTradingSuspended":true,"nxtTradingSuspended":null}}]}""");

		assertEquals(List.of(new StockInfo("A", "이름", "KOSPI", "STOCK", true, "ACTIVE",
			new StockInfo.KoreanMarketDetail(true, true, true, null))), result);
	}

	@Test
	void missingKoreanMarketDetailStaysNull() {
		StockInfo info = respond("""
			{"result":[{"symbol":"A","name":"이름","market":"KOSPI","securityType":"STOCK","isCommonShare":true,"status":"ACTIVE"}]}""").get(0);
		assertEquals(null, info.koreanMarketDetail());
	}

	// 명세상 필수 필드지만, 빠졌을 때 false(= 정리매매 아님, 거래정지 아님)로 조용히 읽히면 위험 종목이 통과한다.
	// 실제로는 역직렬화가 예외를 던진다 (2026-09-29 확인). 종목 하나 때문에 일괄 조회 전체가 실패하지만, 안전한 방향이다
	@Test
	void missingBooleanFieldsFailInsteadOfBeingReadAsFalse() {
		assertThrows(RestClientException.class, () -> respond("""
			{"result":[{"symbol":"A","name":"이름","market":"KOSPI","securityType":"STOCK","isCommonShare":true,"status":"ACTIVE",
			"koreanMarketDetail":{"nxtSupported":true}}]}"""));
		server.reset();
		assertThrows(RestClientException.class, () -> respond("""
			{"result":[{"symbol":"A","name":"이름","market":"KOSPI","securityType":"STOCK","status":"ACTIVE"}]}"""));
	}
}
