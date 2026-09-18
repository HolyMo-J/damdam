package com.damdam.bot.conditionalorder;

import com.damdam.bot.config.TossApiProperties;
import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalOrderServiceTest {

	// live-mode가 꺼져 있으면 등록/수정/취소 모두 실제 API를 전혀 호출하지 않고 모의 결과만 반환해야 한다
	@Test
	void dryRunNeverCallsRealApi() {
		RestClient restClient = RestClient.create("http://localhost:1");
		TossApiProperties properties = new TossApiProperties("http://localhost:1", "dummy", "dummy", "build/tmp/test-token.json");
		TokenService tokenService = new TokenService(restClient, properties, new ObjectMapper());
		ConditionalOrderService service = new ConditionalOrderService(restClient, tokenService, false);

		ConditionalOrderPlacementResult created = service.createAtrOco(1L, "test-client-id", "AAPL", "5",
			"2026-09-30", "160", "160", "150", "149.99");
		assertEquals(ConditionalOrderPlacementResult.Status.SIMULATED, created.status());
		assertNull(created.conditionalOrderId());

		ConditionalOrderPlacementResult modified = service.modifyAtrOco(1L, "existing-id", "5",
			"2026-09-30", "165", "165", "155", "154.99");
		assertEquals(ConditionalOrderPlacementResult.Status.SIMULATED, modified.status());

		boolean canceled = service.cancelConditionalOrder(1L, "existing-id");
		assertTrue(canceled);
	}
}
