package com.damdam.bot.conditionalorder;

import com.damdam.bot.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

// ATR 익절/손절(docs/strategy.md v0) OCO 조건주문. live-mode가 꺼져 있으면(기본값) 절대 실제로 전송하지 않는다
@Service
public class ConditionalOrderService {

	private static final Logger log = LoggerFactory.getLogger(ConditionalOrderService.class);
	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TokenService tokenService;
	private final boolean liveMode;

	public ConditionalOrderService(RestClient tossRestClient, TokenService tokenService,
			@Value("${damdam.orders.live-mode}") boolean liveMode) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
		this.liveMode = liveMode;
	}

	// 해당 종목의 진행 중(OPEN) 조건주문을 찾는다. 토스는 종목당 조건주문을 1개만 허용한다
	public Optional<ConditionalOrderDetail> findOpenConditionalOrder(long accountSeq, String symbol) {
		String uri = UriComponentsBuilder.fromPath("/api/v1/conditional-orders")
			.queryParam("status", "OPEN")
			.queryParam("symbol", symbol)
			.queryParam("limit", 1)
			.toUriString();

		ConditionalOrdersListResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
			.retrieve()
			.body(ConditionalOrdersListResponse.class);

		if (response == null || response.result().conditionalOrders().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(response.result().conditionalOrders().get(0));
	}

	// 익절/손절 OCO를 새로 등록한다
	public ConditionalOrderPlacementResult createAtrOco(long accountSeq, String clientOrderId, String symbol,
			String quantity, String expireDate,
			String takeProfitTrigger, String takeProfitOrderPrice,
			String stopLossTrigger, String stopLossOrderPrice) {
		if (!liveMode) {
			log.warn("[모의 조건주문 등록] 실제로 전송하지 않습니다: {} {}주 OCO 익절 {} / 손절 {} (clientOrderId={})",
				symbol, quantity, takeProfitTrigger, stopLossTrigger, clientOrderId);
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.SIMULATED, null, null);
		}

		ConditionRequest first = new ConditionRequest("SELL", takeProfitTrigger, takeProfitOrderPrice);
		ConditionRequest second = new ConditionRequest("SELL", stopLossTrigger, stopLossOrderPrice);
		ConditionalOrderCreateRequest request = new ConditionalOrderCreateRequest(
			symbol, "OCO", quantity, "LIMIT", clientOrderId, expireDate, first, second);

		try {
			ConditionalOrderCreateResponse response = restClient.post()
				.uri("/api/v1/conditional-orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.body(request)
				.retrieve()
				.body(ConditionalOrderCreateResponse.class);

			String conditionalOrderId = response == null ? null : response.result().conditionalOrderId();
			log.warn("[실조건주문 등록] {} {}주 OCO 익절 {} / 손절 {} 접수됨, conditionalOrderId={}",
				symbol, quantity, takeProfitTrigger, stopLossTrigger, conditionalOrderId);
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.PLACED, conditionalOrderId, null);
		} catch (RestClientResponseException e) {
			log.warn("[실조건주문 등록 실패] {} OCO 익절 {} / 손절 {} 거부됨: {}", symbol, takeProfitTrigger, stopLossTrigger, e.getMessage());
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.FAILED, null, e.getMessage());
		}
	}

	// 기존 조건주문을 수정한다 (수정하면 새 conditionalOrderId가 발급되고 기존 ID는 무효화됨)
	public ConditionalOrderPlacementResult modifyAtrOco(long accountSeq, String conditionalOrderId,
			String quantity, String expireDate,
			String takeProfitTrigger, String takeProfitOrderPrice,
			String stopLossTrigger, String stopLossOrderPrice) {
		if (!liveMode) {
			log.warn("[모의 조건주문 수정] 실제로 전송하지 않습니다: {} {}주 OCO 익절 {} / 손절 {}",
				conditionalOrderId, quantity, takeProfitTrigger, stopLossTrigger);
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.SIMULATED, null, null);
		}

		ConditionRequest first = new ConditionRequest("SELL", takeProfitTrigger, takeProfitOrderPrice);
		ConditionRequest second = new ConditionRequest("SELL", stopLossTrigger, stopLossOrderPrice);
		ConditionalOrderModifyRequest request = new ConditionalOrderModifyRequest(
			"OCO", quantity, "LIMIT", expireDate, first, second);

		try {
			ConditionalOrderModifyResponse response = restClient.post()
				.uri("/api/v1/conditional-orders/{id}/modify", conditionalOrderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.body(request)
				.retrieve()
				.body(ConditionalOrderModifyResponse.class);

			String newId = response == null ? null : response.result().conditionalOrderId();
			log.warn("[실조건주문 수정] {} -> {} {}주 OCO 익절 {} / 손절 {} 접수됨",
				conditionalOrderId, newId, quantity, takeProfitTrigger, stopLossTrigger);
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.PLACED, newId, null);
		} catch (RestClientResponseException e) {
			log.warn("[실조건주문 수정 실패] {} OCO 익절 {} / 손절 {} 거부됨: {}",
				conditionalOrderId, takeProfitTrigger, stopLossTrigger, e.getMessage());
			return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.FAILED, null, e.getMessage());
		}
	}

	// 조건주문을 취소한다
	public boolean cancelConditionalOrder(long accountSeq, String conditionalOrderId) {
		if (!liveMode) {
			log.warn("[모의 조건주문 취소] 실제로 전송하지 않습니다: {}", conditionalOrderId);
			return true;
		}

		try {
			restClient.delete()
				.uri("/api/v1/conditional-orders/{id}", conditionalOrderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.retrieve()
				.toBodilessEntity();
			log.warn("[실조건주문 취소] {} 취소됨", conditionalOrderId);
			return true;
		} catch (RestClientResponseException e) {
			log.warn("[실조건주문 취소 실패] {} 거부됨: {}", conditionalOrderId, e.getMessage());
			return false;
		}
	}
}
