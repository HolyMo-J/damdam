package com.damdam.bot.conditionalorder;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSizeCorrectionTest {

	// 감시가는 그대로 두고 지정가만 호가에 맞춰 바뀌는지 본다
	private static final AtrOcoPricing.Prices CURRENT = new AtrOcoPricing.Prices("24520", "24520", "23980", "23979");

	// 공식 명세의 호가 단위 불일치 응답 예시와 같은 모양
	private static String body(String field, String tickSize, String lower, String upper) {
		return """
			{"error":{"requestId":"01HXYZ","code":"invalid-request","message":"주문 가격이 호가 단위에 맞지 않습니다.",
			"data":{"field":"%s","tickSize":"%s","nearestPrices":["%s","%s"]}}}
			""".formatted(field, tickSize, lower, upper);
	}

	// 감시가에 닿아도 그 가격에 지정가 주문이 새로 걸릴 뿐 즉시 체결되지 않으므로(공식 명세), 익절도 손절처럼
	// 아래쪽(확실한 체결) 방향으로 옮긴다 (docs/review-tasks.md 10번, 2026-09-23 방향 변경)
	@Test
	void takeProfitOrderPriceMovesDown() {
		Optional<AtrOcoPricing.Prices> fixed = TickSizeCorrection.apply(body("first.orderPrice", "50", "24500", "24550"), CURRENT);

		assertEquals(new AtrOcoPricing.Prices("24520", "24500", "23980", "23979"), fixed.orElseThrow());
	}

	@Test
	void stopLossOrderPriceMovesDown() {
		Optional<AtrOcoPricing.Prices> fixed = TickSizeCorrection.apply(body("second.orderPrice", "50", "23950", "24000"), CURRENT);

		assertEquals(new AtrOcoPricing.Prices("24520", "24520", "23980", "23950"), fixed.orElseThrow());
	}

	// 서버가 준 값이 예상 방향과 다르면 추측해서 고치지 않는다
	@Test
	void refusesWhenTheDirectionIsUnexpected() {
		// 익절인데 lower가 원래 가격보다 높음
		assertTrue(TickSizeCorrection.apply(body("first.orderPrice", "50", "24550", "24600"), CURRENT).isEmpty());
		// 손절인데 lower가 원래 가격보다 높음
		assertTrue(TickSizeCorrection.apply(body("second.orderPrice", "50", "24000", "24050"), CURRENT).isEmpty());
	}

	// 한 호가를 넘는 큰 이동은 응답이 이상한 것으로 보고 거부한다
	@Test
	void refusesMovesLargerThanOneTick() {
		assertTrue(TickSizeCorrection.apply(body("first.orderPrice", "50", "24400", "24450"), CURRENT).isEmpty());
		assertTrue(TickSizeCorrection.apply(body("second.orderPrice", "50", "23800", "24000"), CURRENT).isEmpty());
	}

	@Test
	void refusesFieldsItDoesNotKnow() {
		assertTrue(TickSizeCorrection.apply(body("first.triggerPrice", "50", "24500", "24550"), CURRENT).isEmpty());
		assertTrue(TickSizeCorrection.apply(body("price", "50", "24500", "24550"), CURRENT).isEmpty());
	}

	@Test
	void refusesMalformedOrIncompleteBodies() {
		assertTrue(TickSizeCorrection.apply("", CURRENT).isEmpty());
		assertTrue(TickSizeCorrection.apply("not json", CURRENT).isEmpty());
		assertTrue(TickSizeCorrection.apply("{\"error\":{\"data\":{\"field\":\"first.orderPrice\"}}}", CURRENT).isEmpty());
		assertTrue(TickSizeCorrection.apply(body("first.orderPrice", "abc", "24500", "24550"), CURRENT).isEmpty());
	}
}
