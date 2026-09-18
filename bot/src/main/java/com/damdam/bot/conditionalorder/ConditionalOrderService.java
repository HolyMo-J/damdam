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
	// 호가 단위 불일치 응답으로 지정가를 보정한 뒤 다시 보내는 최대 횟수. 익절과 손절이 하나씩 따로 지적될 수 있어서 2회
	private static final int MAX_TICK_CORRECTIONS = 2;

	private final RestClient restClient;
	private final TokenService tokenService;
	private final boolean liveMode;

	public ConditionalOrderService(RestClient tossRestClient, TokenService tokenService,
			@Value("${damdam.orders.live-mode}") boolean liveMode) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
		this.liveMode = liveMode;
	}

	// 해당 종목의 진행 중(OPEN) OCO 조건주문을 찾는다. 토스는 종목당 OCO/OTO를 1개만 허용한다.
	// 이 API는 토스 앱에서 직접 만든 조건주문(SINGLE 등)도 함께 돌려주므로, 타입이 OCO인 것만 봇이 관리하는 대상으로 본다
	public Optional<ConditionalOrderDetail> findOpenConditionalOrder(long accountSeq, String symbol) {
		String uri = UriComponentsBuilder.fromPath("/api/v1/conditional-orders")
			.queryParam("status", "OPEN")
			.queryParam("symbol", symbol)
			.queryParam("limit", 100)
			.toUriString();

		ConditionalOrdersListResponse response = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
			.retrieve()
			.body(ConditionalOrdersListResponse.class);

		if (response == null) {
			return Optional.empty();
		}
		return response.result().conditionalOrders().stream()
			.filter(order -> "OCO".equals(order.type()))
			.findFirst();
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

		AtrOcoPricing.Prices initial = new AtrOcoPricing.Prices(takeProfitTrigger, takeProfitOrderPrice, stopLossTrigger, stopLossOrderPrice);
		return sendWithTickCorrection("등록", symbol, initial, (prices, attempt) -> {
			ConditionRequest first = new ConditionRequest("SELL", prices.takeProfitTrigger(), prices.takeProfitOrderPrice());
			ConditionRequest second = new ConditionRequest("SELL", prices.stopLossTrigger(), prices.stopLossOrderPrice());
			// 거부된 요청은 주문이 생기지 않았다. 재시도는 서버의 멱등성 캐시와 겹치지 않게 새 ID를 쓴다
			String idForAttempt = attempt == 0 ? clientOrderId : clientOrderId + "-r" + attempt;
			ConditionalOrderCreateRequest request = new ConditionalOrderCreateRequest(
				symbol, "OCO", quantity, "LIMIT", idForAttempt, expireDate, first, second);

			ConditionalOrderCreateResponse response = restClient.post()
				.uri("/api/v1/conditional-orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.body(request)
				.retrieve()
				.body(ConditionalOrderCreateResponse.class);
			return response == null ? null : response.result().conditionalOrderId();
		});
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

		AtrOcoPricing.Prices initial = new AtrOcoPricing.Prices(takeProfitTrigger, takeProfitOrderPrice, stopLossTrigger, stopLossOrderPrice);
		return sendWithTickCorrection("수정", conditionalOrderId, initial, (prices, attempt) -> {
			ConditionRequest first = new ConditionRequest("SELL", prices.takeProfitTrigger(), prices.takeProfitOrderPrice());
			ConditionRequest second = new ConditionRequest("SELL", prices.stopLossTrigger(), prices.stopLossOrderPrice());
			ConditionalOrderModifyRequest request = new ConditionalOrderModifyRequest(
				"OCO", quantity, "LIMIT", expireDate, first, second);

			ConditionalOrderModifyResponse response = restClient.post()
				.uri("/api/v1/conditional-orders/{id}/modify", conditionalOrderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.body(request)
				.retrieve()
				.body(ConditionalOrderModifyResponse.class);
			return response == null ? null : response.result().conditionalOrderId();
		});
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

	// 호가 단위 불일치(400)로 거부되면 서버가 알려준 nearestPrices로 지정가를 보정해 다시 보낸다 (TickSizeCorrection 참고).
	// 그 밖의 거부, 보정할 수 없는 응답, 재시도 횟수 초과는 실패로 돌려주고 호출한 쪽이 알린다
	private ConditionalOrderPlacementResult sendWithTickCorrection(String action, String target,
			AtrOcoPricing.Prices initial, OcoSender sender) {
		AtrOcoPricing.Prices prices = initial;
		for (int attempt = 0; ; attempt++) {
			try {
				String conditionalOrderId = sender.send(prices, attempt);
				log.warn("[실조건주문 {}] {} OCO 익절 {} / 손절 {} 접수됨, conditionalOrderId={}",
					action, target, prices.takeProfitTrigger(), prices.stopLossTrigger(), conditionalOrderId);
				return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.PLACED, conditionalOrderId, null);
			} catch (RestClientResponseException e) {
				Optional<AtrOcoPricing.Prices> corrected = e.getStatusCode().value() == 400 && attempt < MAX_TICK_CORRECTIONS
					? TickSizeCorrection.apply(e.getResponseBodyAsString(), prices)
					: Optional.empty();
				if (corrected.isEmpty()) {
					log.warn("[실조건주문 {} 실패] {} OCO 익절 {} / 손절 {} 거부됨: {}",
						action, target, prices.takeProfitTrigger(), prices.stopLossTrigger(), e.getMessage());
					return new ConditionalOrderPlacementResult(ConditionalOrderPlacementResult.Status.FAILED, null, e.getMessage());
				}
				log.warn("[실조건주문 {}] {} 호가 단위 불일치로 거부돼 지정가를 보정해 다시 보냅니다 (익절 {} -> {}, 손절 {} -> {}, 재시도 {}회차)",
					action, target, prices.takeProfitOrderPrice(), corrected.get().takeProfitOrderPrice(),
					prices.stopLossOrderPrice(), corrected.get().stopLossOrderPrice(), attempt + 1);
				prices = corrected.get();
			}
		}
	}

	@FunctionalInterface
	private interface OcoSender {
		// 성공하면 conditionalOrderId를 반환하고, 서버가 거부하면 RestClientResponseException을 던진다
		String send(AtrOcoPricing.Prices prices, int attempt);
	}
}
