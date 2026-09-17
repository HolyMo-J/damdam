package com.damdam.bot.orders;

import com.damdam.bot.config.TossApiProperties;
import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderPlacementServiceTest {

	// live-mode가 꺼져 있으면 실제 API를 전혀 호출하지 않고 모의 결과만 반환해야 한다
	@Test
	void dryRunNeverCallsRealApi() {
		RestClient restClient = RestClient.create("http://localhost:1");
		TossApiProperties properties = new TossApiProperties("http://localhost:1", "dummy", "dummy", "build/tmp/test-token.json");
		TokenService tokenService = new TokenService(restClient, properties, new ObjectMapper());
		OrderPlacementService service = new OrderPlacementService(restClient, tokenService, false);

		OrderPlacementResult result = service.placeMarketSell(1L, "test-client-id", "AAPL", "5");

		assertEquals(OrderPlacementResult.Status.SIMULATED, result.status());
		assertNull(result.orderId());
	}
}
