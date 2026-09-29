package com.damdam.bot.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 로컬(127.0.0.1)에 띄운 가짜 서버만 부른다. 토스 API로는 아무것도 나가지 않는다
class HttpClientConfigTest {

	private HttpServer server;
	private CountDownLatch release;

	@BeforeEach
	void startServer() throws IOException {
		release = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		// 응답을 주지 않고 붙잡아 두는 엔드포인트 (테스트가 끝나면 풀어 준다)
		server.createContext("/hang", exchange -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		server.createContext("/ok", exchange -> {
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		release.countDown();
		server.stop(0);
	}

	private RestClient client(Duration connectTimeout, Duration readTimeout) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
			.requestFactory(HttpClientConfig.requestFactory(connectTimeout, readTimeout))
			.build();
	}

	// 응답이 안 오면 무한정 기다리지 않고 읽기 타임아웃에서 끊긴다. 예외 종류가 ResourceAccessException(원인 HttpTimeoutException)인 것이
	// OrderPlacementService가 "접수 여부 불명"을 판정하는 근거라서 종류까지 함께 고정한다
	@Test
	void readTimeoutStopsWaitingAndThrowsResourceAccessException() {
		RestClient client = client(Duration.ofSeconds(1), Duration.ofMillis(300));

		long start = System.nanoTime();
		ResourceAccessException e = assertThrows(ResourceAccessException.class,
			() -> client.get().uri("/hang").retrieve().body(String.class));
		long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

		assertInstanceOf(HttpTimeoutException.class, e.getCause());
		assertTrue(elapsedMillis < 5_000, "타임아웃이 걸리지 않고 오래 기다렸다: " + elapsedMillis + "ms");
	}

	@Test
	void normalResponseIsNotAffectedByTimeouts() {
		RestClient client = client(Duration.ofSeconds(1), Duration.ofSeconds(5));

		assertEquals("ok", client.get().uri("/ok").retrieve().body(String.class));
	}
}
