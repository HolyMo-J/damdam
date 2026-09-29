package com.damdam.bot.orders;

import com.damdam.bot.config.TossApiProperties;
import com.damdam.bot.token.TokenService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

	// 응답이 안 와서 읽기 타임아웃이 나면 거부(FAILED)가 아니라 접수 여부 불명(UNKNOWN)으로 돌려줘야 한다.
	// live-mode를 켜지만 주소는 로컬 가짜 서버(127.0.0.1)라 토스 API로는 아무것도 나가지 않는다
	@Test
	void readTimeoutOnOrderPostReturnsUnknownNotFailed() throws IOException {
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/v1/orders", exchange -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		server.start();
		try {
			JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
			factory.setReadTimeout(Duration.ofMillis(300));
			RestClient restClient = RestClient.builder()
				.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
				.requestFactory(factory)
				.build();
			TokenService tokenService = mock(TokenService.class);
			when(tokenService.getAccessToken()).thenReturn("dummy-token");
			OrderPlacementService service = new OrderPlacementService(restClient, tokenService, true);

			OrderPlacementResult result = service.placeMarketSell(1L, "test-client-id", "AAA", "5");

			assertEquals(OrderPlacementResult.Status.UNKNOWN, result.status());
			assertNull(result.orderId());
			assertEquals("test-client-id", result.clientOrderId());
			assertNotNull(result.errorMessage());
		} finally {
			release.countDown();
			server.stop(0);
		}
	}
}
