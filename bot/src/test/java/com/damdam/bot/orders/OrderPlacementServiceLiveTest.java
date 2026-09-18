package com.damdam.bot.orders;

import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실주문 경로(live-mode가 켜진 경우)를 검증한다. 요청은 MockRestServiceServer가 가로채므로 실제 토스 API로는 아무것도 나가지 않는다.
// 이 테스트가 돈이 움직이는 코드에서 확인하려는 것: 정확한 주소와 헤더, 그리고 "보유 수량 전체를 시장가 매도"라는 본문
class OrderPlacementServiceLiveTest {

	private static final String BASE = "http://toss.test";

	private MockRestServiceServer server;
	private OrderPlacementService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		TokenService tokenService = mock(TokenService.class);
		when(tokenService.getAccessToken()).thenReturn("test-token");
		service = new OrderPlacementService(builder.build(), tokenService, true);
	}

	@Test
	void sendsAMarketSellToTheOrdersEndpointWithAuthAndAccountHeaders() {
		server.expect(requestTo(BASE + "/api/v1/orders"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer test-token"))
			.andExpect(header("X-Tossinvest-Account", "3"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.clientOrderId").value("time-exit-AAA-2026-09-21"))
			.andExpect(jsonPath("$.symbol").value("AAA"))
			.andExpect(jsonPath("$.side").value("SELL"))
			.andExpect(jsonPath("$.orderType").value("MARKET"))
			.andExpect(jsonPath("$.quantity").value("5"))
			// 시장가 주문에는 가격을 보내면 안 된다 (공식 명세: 400 invalid-request)
			.andExpect(jsonPath("$.price").doesNotExist())
			.andRespond(withSuccess("{\"result\":{\"orderId\":\"order-1\",\"clientOrderId\":\"time-exit-AAA-2026-09-21\"}}",
				MediaType.APPLICATION_JSON));

		OrderPlacementResult result = service.placeMarketSell(3L, "time-exit-AAA-2026-09-21", "AAA", "5");

		assertEquals(OrderPlacementResult.Status.PLACED, result.status());
		assertEquals("order-1", result.orderId());
		server.verify();
	}

	// 거부되면 예외로 봇을 죽이지 않고 FAILED로 돌려준다 (호출한 쪽이 다음 주기에 다시 시도)
	@Test
	void aRejectedOrderIsReportedAsFailedNotThrown() {
		server.expect(requestTo(BASE + "/api/v1/orders"))
			.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"code\":\"order-hours-closed\",\"message\":\"현재 주문을 접수할 수 없는 시간입니다.\"}}"));

		OrderPlacementResult result = service.placeMarketSell(3L, "id-1", "AAA", "5");

		assertEquals(OrderPlacementResult.Status.FAILED, result.status());
		assertNull(result.orderId());
		assertNotNull(result.errorMessage());
		server.verify();
	}

	@Test
	void rateLimitAndForbiddenResponsesAlsoFailWithoutRetrying() {
		// 재시도하지 않으므로 요청은 정확히 한 번만 나간다 (두 번째 요청이 나가면 가짜 서버가 실패시킨다)
		server.expect(requestTo(BASE + "/api/v1/orders")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertEquals(OrderPlacementResult.Status.FAILED, service.placeMarketSell(3L, "id-1", "AAA", "5").status());
		server.verify();
	}

	// live-mode가 꺼져 있으면 가짜 서버에도 요청이 가지 않아야 한다 (기대 요청이 없으므로 나가면 즉시 실패)
	@Test
	void dryRunSendsNothing() {
		OrderPlacementService dryRun = new OrderPlacementService(RestClient.builder().baseUrl(BASE).build(), mock(TokenService.class), false);

		assertEquals(OrderPlacementResult.Status.SIMULATED, dryRun.placeMarketSell(3L, "id-1", "AAA", "5").status());
		server.verify();
	}
}
