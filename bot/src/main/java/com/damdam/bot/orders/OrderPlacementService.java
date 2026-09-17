package com.damdam.bot.orders;

import com.damdam.bot.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// 실제 돈이 움직이는 주문 생성. live-mode가 꺼져 있으면(기본값) 절대 실제로 전송하지 않는다
@Service
public class OrderPlacementService {

	private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);
	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TokenService tokenService;
	private final boolean liveMode;

	public OrderPlacementService(RestClient tossRestClient, TokenService tokenService,
			@Value("${damdam.orders.live-mode}") boolean liveMode) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
		this.liveMode = liveMode;
	}

	// 보유 수량 전체를 시장가로 매도한다 (시간 청산 전용)
	public OrderPlacementResult placeMarketSell(long accountSeq, String clientOrderId, String symbol, String quantity) {
		if (!liveMode) {
			log.warn("[모의 주문] 실제로 전송하지 않습니다: SELL {} {}주 시장가 (clientOrderId={})", symbol, quantity, clientOrderId);
			return new OrderPlacementResult(OrderPlacementResult.Status.SIMULATED, null, clientOrderId, null);
		}

		OrderCreateRequest request = new OrderCreateRequest(clientOrderId, symbol, "SELL", "MARKET", quantity);
		try {
			OrderCreateResponse response = restClient.post()
				.uri("/api/v1/orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.body(request)
				.retrieve()
				.body(OrderCreateResponse.class);

			String orderId = response == null ? null : response.result().orderId();
			log.warn("[실주문 전송] SELL {} {}주 시장가 접수됨, orderId={}", symbol, quantity, orderId);
			return new OrderPlacementResult(OrderPlacementResult.Status.PLACED, orderId, clientOrderId, null);
		} catch (RestClientResponseException e) {
			log.warn("[실주문 실패] SELL {} {}주 시장가 거부됨: {}", symbol, quantity, e.getMessage());
			return new OrderPlacementResult(OrderPlacementResult.Status.FAILED, null, clientOrderId, e.getMessage());
		}
	}
}
