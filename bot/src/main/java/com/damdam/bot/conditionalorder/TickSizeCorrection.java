package com.damdam.bot.conditionalorder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

// 호가 단위 불일치(400 invalid-request)로 거부됐을 때, 응답의 nearestPrices([lower, upper])로 지정가를 한 호가 안쪽에서 보정한다.
// 호가표를 직접 만들지 않는다: 토스 공식 문서는 예시만 주고 전체 구간표를 주지 않으므로, 서버가 알려주는 값만 근거로 삼는다.
// 방향은 항상 불리하지 않은 쪽이다: 익절 지정가는 위쪽(더 높은 가격에 팔림), 손절 지정가는 아래쪽(더 확실히 체결됨).
// 응답이 예상과 다르면(모르는 필드, 방향이 반대, 한 호가를 넘는 이동) 보정하지 않고 빈 값을 돌려줘서 호출한 쪽이 실패로 처리하게 한다.
final class TickSizeCorrection {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	// OCO 요청에서 first는 익절, second는 손절이다 (AtrOcoManagementService, ConditionalOrderService 참고)
	private static final String TAKE_PROFIT_FIELD = "first.orderPrice";
	private static final String STOP_LOSS_FIELD = "second.orderPrice";

	private TickSizeCorrection() {
	}

	static Optional<AtrOcoPricing.Prices> apply(String errorResponseBody, AtrOcoPricing.Prices current) {
		try {
			JsonNode data = MAPPER.readTree(errorResponseBody).path("error").path("data");
			JsonNode nearest = data.path("nearestPrices");
			if (!nearest.isArray() || nearest.size() != 2) {
				return Optional.empty();
			}
			BigDecimal tickSize = new BigDecimal(data.path("tickSize").asString(""));
			BigDecimal lower = new BigDecimal(nearest.get(0).asString(""));
			BigDecimal upper = new BigDecimal(nearest.get(1).asString(""));
			String field = data.path("field").asString("");

			if (TAKE_PROFIT_FIELD.equals(field)) {
				BigDecimal original = new BigDecimal(current.takeProfitOrderPrice());
				if (upper.compareTo(original) >= 0 && upper.subtract(original).compareTo(tickSize) <= 0) {
					return Optional.of(new AtrOcoPricing.Prices(current.takeProfitTrigger(), upper.toPlainString(),
						current.stopLossTrigger(), current.stopLossOrderPrice()));
				}
			} else if (STOP_LOSS_FIELD.equals(field)) {
				BigDecimal original = new BigDecimal(current.stopLossOrderPrice());
				if (lower.compareTo(original) <= 0 && original.subtract(lower).compareTo(tickSize) <= 0) {
					return Optional.of(new AtrOcoPricing.Prices(current.takeProfitTrigger(), current.takeProfitOrderPrice(),
						current.stopLossTrigger(), lower.toPlainString()));
				}
			}
			return Optional.empty();
		} catch (RuntimeException e) {
			// JSON이 아니거나 숫자가 아니면 보정하지 않는다 (JacksonException, NumberFormatException 모두 RuntimeException)
			return Optional.empty();
		}
	}
}
