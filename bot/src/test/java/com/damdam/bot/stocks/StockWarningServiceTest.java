package com.damdam.bot.stocks;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StockWarningServiceTest {

	private static final String URL = "http://test/api/v1/stocks/005930/warnings";

	private MockRestServiceServer server;
	private StockWarningService service;
	private final List<Long> sleeps = new ArrayList<>();

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://test");
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("dummy-token");
		service = new StockWarningService(builder.build(), tokenService, sleeps::add);
	}

	@Test
	void parsesWarningsAndKeepsUnknownTypes() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer dummy-token"))
			.andRespond(withSuccess("""
				{"result":[
				  {"warningType":"INVESTMENT_WARNING","exchange":"KRX","startDate":"2026-09-01","endDate":null},
				  {"warningType":"BRAND_NEW_CODE","exchange":null,"startDate":null,"endDate":"2026-10-01","extra":1}
				]}""", MediaType.APPLICATION_JSON));

		List<StockWarning> warnings = service.getWarnings("005930");

		assertEquals(List.of(
			new StockWarning("INVESTMENT_WARNING", "KRX", "2026-09-01", null),
			new StockWarning("BRAND_NEW_CODE", null, null, "2026-10-01")), warnings);
		server.verify();
	}

	@Test
	void emptyResultMeansNoActiveWarnings() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));
		assertEquals(List.of(), service.getWarnings("005930"));
	}

	@Test
	void missingResultFieldIsAFailureNotAnEmptyList() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		assertThrows(StockWarningLookupException.class, () -> service.getWarnings("005930"));
	}

	@Test
	void emptyBodyIsAFailureNotAnEmptyList() {
		server.expect(requestTo(URL)).andRespond(withSuccess());
		assertThrows(StockWarningLookupException.class, () -> service.getWarnings("005930"));
	}

	@Test
	void retriesOnceAfterRetryAfterOn429() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2"));
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

		assertEquals(List.of(), service.getWarnings("005930"));
		assertEquals(List.of(2000L), sleeps);
		server.verify();
	}

	@Test
	void usesDefaultWaitWhenRetryAfterIsMissing() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(URL)).andRespond(withSuccess("{\"result\":[]}", MediaType.APPLICATION_JSON));

		service.getWarnings("005930");
		assertEquals(List.of(1000L), sleeps);
	}

	@Test
	void secondRateLimitFailsInsteadOfLoopingForever() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThrows(StockWarningLookupException.class, () -> service.getWarnings("005930"));
		assertEquals(1, sleeps.size());
		server.verify();
	}

	@Test
	void notFoundAndServerErrorsAreWrappedWithoutRetry() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThrows(StockWarningLookupException.class, () -> service.getWarnings("005930"));

		server.reset(); // 앞 기대는 이미 소비됐으므로 다음 응답을 새로 건다
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		assertThrows(StockWarningLookupException.class, () -> service.getWarnings("005930"));
		assertEquals(List.of(), sleeps);
	}
}
