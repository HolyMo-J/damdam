package com.damdam.bot.orderevent;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderEventWebSocketHandlerTest {

	// 실제 주문 없이, 가짜 체결 이벤트 JSON으로 파싱과 로그 출력 경로를 확인한다
	private static final String FILL_EVENT_JSON = """
		{
		  "type": "message",
		  "topic": "personal:order:3",
		  "data": {
		    "event": "FILL",
		    "accountSeq": "3",
		    "order": {
		      "orderId": "test-order-1",
		      "symbol": "AAPL",
		      "side": "BUY",
		      "orderType": "LIMIT",
		      "timeInForce": "DAY",
		      "status": "FILLED",
		      "price": "100.5",
		      "quantity": "10",
		      "orderAmount": null,
		      "currency": "USD",
		      "orderedAt": "2026-06-23T09:30:00.000+09:00",
		      "canceledAt": null,
		      "execution": {
		        "filledQuantity": "10",
		        "averageFilledPrice": "100",
		        "filledAmount": "1000",
		        "commission": "1.23",
		        "tax": "0",
		        "settlementDate": "2026-06-25"
		      }
		    }
		  }
		}
		""";

	@Test
	void parsesFillEventFrame() {
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode node = objectMapper.readTree(FILL_EVENT_JSON);
		OrderEventFrame frame = objectMapper.treeToValue(node, OrderEventFrame.class);

		assertEquals("FILL", frame.data().event());
		assertEquals("AAPL", frame.data().order().symbol());
		assertEquals("10", frame.data().order().execution().filledQuantity());
	}

	@Test
	void handlerProcessesFillEventWithoutError() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, objectMapper, () -> {}, () -> {});

		handler.handleTextMessage(null, new TextMessage(FILL_EVENT_JSON));
	}
}
