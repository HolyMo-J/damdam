package com.damdam.bot.conditionalorder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

// 호가 단위 불일치(400 invalid-request)로 거부됐을 때, 응답의 nearestPrices([lower, upper])로 지정가를 한 호가 안쪽에서 보정한다.
// 호가표를 직접 만들지 않는다: 토스 공식 문서는 예시만 주고 전체 구간표를 주지 않으므로, 서버가 알려주는 값만 근거로 삼는다.
// 방향은 항상 아래쪽(확실한 체결 쪽)이다. 공식 명세(ConditionRequest.orderPrice)는 "트리거 시 이 가격의 지정가 주문을
// 생성한다"고 설명하고, 조건 상태에 ORDERING/ORDERED(생성됨)가 COMPLETED(체결)와 별도로 있다. 이 두 정황으로 미루어
// 볼 때 감시가 도달이 곧바로 체결을 뜻하지는 않는 것으로 보인다(명세가 실행 엔진 동작을 문장으로 확정하지는 않음).
// 이 해석이 맞다면, 익절 지정가를 위쪽으로 옮겼을 때 감시가에 닿은 뒤 가격이 그 지점까지 못 올라가고 반락하면 체결되지
// 않을 위험이 있다(docs/review-tasks.md 10번). 반대로 아래쪽으로 옮기면 감시가 도달 시점에 가격이 이미 그 지정가를
// 넘어서 있으므로 체결 확실성이 더 높다. 이 방향성 결론은 "감시가 도달=즉시 체결이 아니다"라는 해석의 확실성과 무관하게
// 표준적인 지정가 주문 의미론상 성립하므로, 익절도 손절과 같은 방향(아래쪽)으로 옮긴다. 가격은 한 호가만큼 불리해질 수 있다.
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
			String field = data.path("field").asString("");

			if (TAKE_PROFIT_FIELD.equals(field)) {
				BigDecimal original = new BigDecimal(current.takeProfitOrderPrice());
				if (lower.compareTo(original) <= 0 && original.subtract(lower).compareTo(tickSize) <= 0) {
					return Optional.of(new AtrOcoPricing.Prices(current.takeProfitTrigger(), lower.toPlainString(),
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
