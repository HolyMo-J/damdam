package com.damdam.bot.market;

import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PriceServiceTest {

	private MockRestServiceServer server;
	private PriceService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://test");
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("dummy-token");
		service = new PriceService(builder.build(), tokenService);
	}

	@Test
	void sendsOneGetWithCommaJoinedSymbolsAndBearerTokenAndParsesTheResult() {
		server.expect(requestTo("http://test/api/v1/prices?symbols=SPCX,IREN"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer dummy-token"))
			.andRespond(withSuccess("""
				{"result":[
				  {"symbol":"SPCX","timestamp":"2026-10-02T10:41:01.123-04:00","lastPrice":"148.12","currency":"USD"},
				  {"symbol":"IREN","timestamp":null,"lastPrice":"60.5","currency":"USD","unknownField":"ignored"}
				]}""", MediaType.APPLICATION_JSON));

		List<StockPrice> prices = service.getPrices(List.of("SPCX", "IREN"));

		assertEquals(2, prices.size());
		assertEquals(new StockPrice("SPCX", "2026-10-02T10:41:01.123-04:00", "148.12", "USD"), prices.get(0));
		assertEquals("IREN", prices.get(1).symbol());
		assertNull(prices.get(1).timestamp());
		assertEquals("60.5", prices.get(1).lastPrice());
		server.verify();
	}

	@Test
	void rejectsEmptyOrTooManyOrMalformedSymbolsWithoutCallingTheServer() {
		assertThrows(IllegalArgumentException.class, () -> service.getPrices(List.of()));
		assertThrows(IllegalArgumentException.class, () -> service.getPrices(null));

		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= PriceService.MAX_SYMBOLS; i++) {
			tooMany.add("S" + i);
		}
		assertEquals(PriceService.MAX_SYMBOLS + 1, tooMany.size());
		assertThrows(IllegalArgumentException.class, () -> service.getPrices(tooMany));

		for (String bad : List.of("", "A B", "AAPL;DROP", "삼성전자", "A/B", "A,B")) {
			assertThrows(IllegalArgumentException.class, () -> service.getPrices(List.of(bad)), "허용되면 안 되는 심볼: " + bad);
		}
		// 서버에 기대를 걸지 않았으므로 어떤 요청이든 갔다면 MockRestServiceServer가 이미 실패했다
		server.verify();
	}

	@Test
	void acceptsExactlyTheMaximumNumberOfSymbolsAndAllowedPunctuation() {
		List<String> symbols = new ArrayList<>();
		for (int i = 0; i < PriceService.MAX_SYMBOLS - 2; i++) {
			symbols.add("S" + i);
		}
		symbols.add("BRK.B");
		symbols.add("BF-B");
		server.expect(method(HttpMethod.GET)).andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

		assertTrue(service.getPrices(symbols).isEmpty());
		server.verify();
	}

	@Test
	void httpErrorBecomesAFailureWithOnlyTheStatusCodeInTheMessage() {
		server.expect(requestTo("http://test/api/v1/prices?symbols=SPCX"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("secret-response-body").contentType(MediaType.TEXT_PLAIN));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.getPrices(List.of("SPCX")));

		assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
		assertFalse(e.getMessage().contains("secret-response-body"), "응답 본문이 메시지에 섞이면 안 된다");
		assertFalse(e.getMessage().contains("dummy-token"), "토큰이 메시지에 섞이면 안 된다");
	}

	@Test
	void emptyResponseBodyIsAFailureNotAnEmptyResult() {
		server.expect(requestTo("http://test/api/v1/prices?symbols=SPCX"))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThrows(IllegalStateException.class, () -> service.getPrices(List.of("SPCX")));
	}
}
