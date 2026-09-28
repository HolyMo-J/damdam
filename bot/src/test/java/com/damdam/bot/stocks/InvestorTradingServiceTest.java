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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

class InvestorTradingServiceTest {

	private static final String URL = "http://test/api/v1/stocks/005930/investor-trading?count=10";

	private MockRestServiceServer server;
	private InvestorTradingService service;
	private final List<Long> sleeps = new ArrayList<>();

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://test");
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("dummy-token");
		service = new InvestorTradingService(builder.build(), tokenService, sleeps::add);
	}

	private void respondWith(String json) {
		server.expect(requestTo(URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	@Test
	void parsesDateAndInstitutionNetBuyIgnoringOtherFields() {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer dummy-token"))
			.andRespond(withSuccess("""
				{"result":{"nextUntil":"2026-09-01","records":[
				  {"date":"2026-09-28","updatedAt":"2026-09-28T20:05:00+09:00","individual":null,
				   "foreigner":{"buyVolume":"10","sellVolume":"5","netBuyVolume":"5"},
				   "institution":{"buyVolume":"300","sellVolume":"100","netBuyVolume":"200","breakdown":null}},
				  {"date":"2026-09-25","updatedAt":"2026-09-25T20:05:00+09:00",
				   "foreigner":{"buyVolume":"0","sellVolume":"0","netBuyVolume":"0"},
				   "institution":{"buyVolume":"100","sellVolume":"350","netBuyVolume":"-250"}}
				]}}""", MediaType.APPLICATION_JSON));

		List<InvestorTradingRecord> records = service.getRecentRecords("005930", 10);

		// 갱신 시각은 오프셋이 붙은 값(+09:00)을 같은 순간의 Instant로 읽는다
		assertEquals(List.of(
			new InvestorTradingRecord(LocalDate.parse("2026-09-28"), new BigDecimal("200"), Instant.parse("2026-09-28T11:05:00Z")),
			new InvestorTradingRecord(LocalDate.parse("2026-09-25"), new BigDecimal("-250"), Instant.parse("2026-09-25T11:05:00Z"))), records);
		server.verify();
	}

	@Test
	void missingUpdatedAtIsAllowedBecauseItIsOnlyForObservation() {
		respondWith("{\"result\":{\"records\":[{\"date\":\"2026-09-28\",\"institution\":{\"netBuyVolume\":\"7\"}}]}}");
		assertEquals(List.of(new InvestorTradingRecord(LocalDate.parse("2026-09-28"), new BigDecimal("7"), null)),
			service.getRecentRecords("005930", 10));
	}

	@Test
	void malformedJsonIsWrappedAsALookupFailure() {
		server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));
	}

	@Test
	void noRecordsIsAnEmptyListNotAFailure() {
		respondWith("{\"result\":{\"records\":[],\"nextUntil\":null}}");
		assertEquals(List.of(), service.getRecentRecords("005930", 10));
	}

	@Test
	void emptyBodyOrMissingRecordsFieldIsAFailure() {
		server.expect(requestTo(URL)).andRespond(withSuccess());
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));

		server.reset();
		respondWith("{}");
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));

		server.reset();
		respondWith("{\"result\":{}}");
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));
	}

	@Test
	void recordMissingDateOrInstitutionNetBuyFailsTheWholeLookup() {
		// 한 건이라도 값이 빠지면 나머지를 믿고 쓰지 않는다. 빠진 날을 0으로 채우면 조용히 틀린 신호가 된다
		respondWith("{\"result\":{\"records\":[{\"date\":\"2026-09-28\",\"institution\":{\"buyVolume\":\"1\"}}]}}");
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));

		server.reset();
		respondWith("{\"result\":{\"records\":[{\"date\":\"2026-09-28\"}]}}");
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));

		server.reset();
		respondWith("{\"result\":{\"records\":[{\"institution\":{\"netBuyVolume\":\"5\"}}]}}");
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));
	}

	@Test
	void retriesOnceAfterRetryAfterOn429() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"));
		respondWith("{\"result\":{\"records\":[]}}");

		assertEquals(List.of(), service.getRecentRecords("005930", 10));
		assertEquals(List.of(3000L), sleeps);
		server.verify();
	}

	@Test
	void otherHttpErrorsAreWrappedWithoutRetry() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)); // 국내 종목이 아니면 400 unsupported-market
		assertThrows(StockLookupException.class, () -> service.getRecentRecords("005930", 10));
		assertEquals(List.of(), sleeps);
	}

	@Test
	void countOutsideSpecRangeIsRejectedBeforeAnyRequest() {
		assertThrows(IllegalArgumentException.class, () -> service.getRecentRecords("005930", 0));
		assertThrows(IllegalArgumentException.class, () -> service.getRecentRecords("005930", 101));
		server.verify(); // 기대한 요청이 없으므로, 요청이 나갔다면 위에서 다른 예외가 났을 것이다
	}
}
