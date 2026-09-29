package com.damdam.bot.orders;

import com.damdam.bot.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
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
		} catch (ResourceAccessException e) {
			// 서버가 오류 응답을 준 게 아니라 응답 자체를 못 받은 경우(읽기 타임아웃 등)다. 요청이 서버에 도달했는지 알 수 없어서
			// 거부(FAILED)와 구분한다. 연결 자체가 안 된 경우도 같은 예외라 구분하지 않고 불명으로 둔다(안전한 쪽)
			log.warn("[실주문 응답 없음] SELL {} {}주 시장가, 접수 여부를 알 수 없습니다: {}", symbol, quantity, e.getMessage());
			return new OrderPlacementResult(OrderPlacementResult.Status.UNKNOWN, null, clientOrderId, e.getMessage());
		}
	}
}
