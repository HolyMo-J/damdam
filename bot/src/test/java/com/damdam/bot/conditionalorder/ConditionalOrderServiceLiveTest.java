package com.damdam.bot.conditionalorder;

import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// live-mode가 켜진 경로를 MockRestServiceServer로 검증한다. 실제 토스 API로는 아무것도 나가지 않는다 (요청은 가짜 서버가 가로챈다)
class ConditionalOrderServiceLiveTest {

	private static final String BASE = "http://toss.test";
	private static final String CREATE_URL = BASE + "/api/v1/conditional-orders";

	private MockRestServiceServer server;
	private ConditionalOrderService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("test-token");
		service = new ConditionalOrderService(builder.build(), tokenService, true);
	}

	private static String tickError(String field, String tickSize, String lower, String upper) {
		return """
			{"error":{"requestId":"01HXYZ","code":"invalid-request","message":"주문 가격이 호가 단위에 맞지 않습니다.",
			"data":{"field":"%s","tickSize":"%s","nearestPrices":["%s","%s"]}}}
			""".formatted(field, tickSize, lower, upper);
	}

	private static final String CREATED = "{\"result\":{\"conditionalOrderId\":\"co-1\"}}";

	private ConditionalOrderPlacementResult create() {
		return service.createAtrOco(3L, "atr-oco-005930-1", "005930", "2", "2026-10-01", "71234", "71234", "70234", "70233");
	}

	@Test
	void sendsTheOcoWithAuthAndAccountHeaders() {
		server.expect(requestTo(CREATE_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-token"))
			.andExpect(header("X-Tossinvest-Account", "3"))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.type").value("OCO"))
			.andExpect(jsonPath("$.orderType").value("LIMIT"))
			.andExpect(jsonPath("$.first.triggerPrice").value("71234"))
			.andExpect(jsonPath("$.second.orderPrice").value("70233"))
			.andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

		ConditionalOrderPlacementResult result = create();

		assertEquals(ConditionalOrderPlacementResult.Status.PLACED, result.status());
		assertEquals("co-1", result.conditionalOrderId());
		server.verify();
	}

	// 익절, 손절이 차례로 지적되면 각각 보정해서 다시 보내고, 재시도에는 새 clientOrderId를 쓴다
	@Test
	void correctsBothLegsAcrossRetriesAndUsesFreshClientOrderIds() {
		server.expect(requestTo(CREATE_URL))
			.andExpect(jsonPath("$.clientOrderId").value("atr-oco-005930-1"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(tickError("first.orderPrice", "100", "71200", "71300")));
		server.expect(requestTo(CREATE_URL))
			.andExpect(jsonPath("$.clientOrderId").value("atr-oco-005930-1-r1"))
			.andExpect(jsonPath("$.first.orderPrice").value("71200"))
			.andExpect(jsonPath("$.first.triggerPrice").value("71234"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(tickError("second.orderPrice", "100", "70200", "70300")));
		server.expect(requestTo(CREATE_URL))
			.andExpect(jsonPath("$.clientOrderId").value("atr-oco-005930-1-r2"))
			.andExpect(jsonPath("$.first.orderPrice").value("71200"))
			.andExpect(jsonPath("$.second.orderPrice").value("70200"))
			.andExpect(jsonPath("$.second.triggerPrice").value("70234"))
			.andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

		ConditionalOrderPlacementResult result = create();

		assertEquals(ConditionalOrderPlacementResult.Status.PLACED, result.status());
		server.verify();
	}

	// 보정할 수 없는 400(모르는 필드 등)은 다시 보내지 않고 실패로 돌려준다 (호출한 쪽이 알림을 보낸다)
	@Test
	void doesNotRetryWhenTheErrorCannotBeCorrected() {
		server.expect(requestTo(CREATE_URL))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(tickError("first.triggerPrice", "100", "71200", "71300")));

		ConditionalOrderPlacementResult result = create();

		assertEquals(ConditionalOrderPlacementResult.Status.FAILED, result.status());
		assertNull(result.conditionalOrderId());
		server.verify();
	}

	// 서버가 계속 거부해도 무한 재시도하지 않는다 (처음 1회 + 보정 2회 = 3번까지만)
	@Test
	void givesUpAfterTheRetryLimit() {
		for (int i = 0; i < 3; i++) {
			server.expect(requestTo(CREATE_URL))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
					.body(tickError("first.orderPrice", "100", "71200", "71300")));
		}

		ConditionalOrderPlacementResult result = create();

		assertEquals(ConditionalOrderPlacementResult.Status.FAILED, result.status());
		server.verify();
	}

	@Test
	void otherStatusCodesAreNotRetried() {
		server.expect(requestTo(CREATE_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

		ConditionalOrderPlacementResult result = create();

		assertEquals(ConditionalOrderPlacementResult.Status.FAILED, result.status());
		server.verify();
	}

	@Test
	void modifyAlsoCorrectsTheTickSize() {
		String modifyUrl = BASE + "/api/v1/conditional-orders/co-1/modify";
		server.expect(requestTo(modifyUrl))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body(tickError("second.orderPrice", "100", "70200", "70300")));
		server.expect(requestTo(modifyUrl))
			.andExpect(jsonPath("$.second.orderPrice").value("70200"))
			.andRespond(withSuccess("{\"result\":{\"conditionalOrderId\":\"co-2\"}}", MediaType.APPLICATION_JSON));

		ConditionalOrderPlacementResult result = service.modifyAtrOco(3L, "co-1", "2", "2026-10-01",
			"71300", "71300", "70234", "70233");

		assertEquals(ConditionalOrderPlacementResult.Status.PLACED, result.status());
		assertEquals("co-2", result.conditionalOrderId());
		server.verify();
	}

	private static String listBody(String... orders) {
		return "{\"result\":{\"conditionalOrders\":[" + String.join(",", orders) + "],\"nextCursor\":null,\"hasNext\":false}}";
	}

	private static String orderJson(String id, String type, String quantity) {
		return "{\"conditionalOrderId\":\"" + id + "\",\"type\":\"" + type + "\",\"status\":\"WATCHING\",\"symbol\":\"005930\","
			+ "\"quantity\":\"" + quantity + "\"}";
	}

	// 토스 앱에서 직접 만든 단일 조건주문을 봇이 OCO로 착각해 수정(덮어쓰기)하면 안 된다
	@Test
	void findsOnlyOcoOrdersAndIgnoresSingleOnesMadeInTheApp() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v1/conditional-orders?status=OPEN&symbol=005930")))
			.andRespond(withSuccess(listBody(orderJson("single-1", "SINGLE", "1"), orderJson("oco-1", "OCO", "2")),
				MediaType.APPLICATION_JSON));

		var found = service.findOpenConditionalOrder(3L, "005930");

		assertEquals("oco-1", found.orElseThrow().conditionalOrderId());
		server.verify();
	}

	@Test
	void returnsNothingWhenOnlySingleOrdersExist() {
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v1/conditional-orders?status=OPEN")))
			.andRespond(withSuccess(listBody(orderJson("single-1", "SINGLE", "1")), MediaType.APPLICATION_JSON));

		assertTrue(service.findOpenConditionalOrder(3L, "005930").isEmpty());
		server.verify();
	}
}
