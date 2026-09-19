package com.damdam.bot.orderevent;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.conditionalorder.ConditionalOrderService;
import com.damdam.bot.config.TossApiProperties;
import com.damdam.bot.control.TradingHaltSwitch;
import com.damdam.bot.holdings.HoldingsService;
import com.damdam.bot.market.AtrService;
import com.damdam.bot.market.MarketDataService;
import com.damdam.bot.records.TradeRecordWriter;
import com.damdam.bot.token.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		TradeRecordWriter tradeRecordWriter = new TradeRecordWriter("build/tmp/test-trades-noop.csv");
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, objectMapper, tradeRecordWriter,
			newUnreachableAtrOcoManagementService(), (key, message) -> {}, () -> {}, () -> {});

		handler.handleTextMessage(null, new TextMessage(FILL_EVENT_JSON));
	}

	@Test
	void fillEventIsRecordedToCsv(@TempDir Path tempDir) throws Exception {
		Path csvPath = tempDir.resolve("trades.csv");
		ObjectMapper objectMapper = new ObjectMapper();
		TradeRecordWriter tradeRecordWriter = new TradeRecordWriter(csvPath.toString());
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, objectMapper, tradeRecordWriter,
			newUnreachableAtrOcoManagementService(), (key, message) -> {}, () -> {}, () -> {});

		handler.handleTextMessage(null, new TextMessage(FILL_EVENT_JSON));

		String csvContent = Files.readString(csvPath);
		assertTrue(csvContent.contains("test-order-1"));
		assertTrue(csvContent.contains("AAPL"));
		assertTrue(csvContent.contains("FILL"));
	}

	// 매도 체결(OCO 트리거 포함)은 시간 청산 여부와 무관하게 항상 알림을 보내야 한다
	@Test
	void sellFillNotifies() throws Exception {
		String sellFillJson = FILL_EVENT_JSON.replace("\"side\": \"BUY\"", "\"side\": \"SELL\"");
		ObjectMapper objectMapper = new ObjectMapper();
		TradeRecordWriter tradeRecordWriter = new TradeRecordWriter("build/tmp/test-trades-sell.csv");
		List<String> alertKeys = new ArrayList<>();
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, objectMapper, tradeRecordWriter,
			newUnreachableAtrOcoManagementService(), (key, message) -> alertKeys.add(key), () -> {}, () -> {});

		handler.handleTextMessage(null, new TextMessage(sellFillJson));

		assertTrue(alertKeys.contains("sell-fill-AAPL"));
	}

	// 매수 체결은 매도 체결 알림 대상이 아니다
	@Test
	void buyFillDoesNotTriggerSellFillNotification() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		TradeRecordWriter tradeRecordWriter = new TradeRecordWriter("build/tmp/test-trades-buy.csv");
		List<String> alertKeys = new ArrayList<>();
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, objectMapper, tradeRecordWriter,
			newUnreachableAtrOcoManagementService(), (key, message) -> alertKeys.add(key), () -> {}, () -> {});

		handler.handleTextMessage(null, new TextMessage(FILL_EVENT_JSON));

		assertTrue(alertKeys.stream().noneMatch(key -> key.startsWith("sell-fill-")));
	}

	// BUY 체결 시 OCO 갱신을 시도하지만, 네트워크로 나가지 않는 가짜 주소라 실패하고 내부에서 로그로만 처리된다
	private static AtrOcoManagementService newUnreachableAtrOcoManagementService() {
		RestClient restClient = RestClient.create("http://localhost:1");
		TossApiProperties properties = new TossApiProperties("http://localhost:1", "dummy", "dummy", "build/tmp/test-token.json");
		TokenService tokenService = new TokenService(restClient, properties, new ObjectMapper());
		HoldingsService holdingsService = new HoldingsService(restClient, tokenService);
		AtrService atrService = new AtrService(new MarketDataService(restClient, tokenService));
		ConditionalOrderService conditionalOrderService = new ConditionalOrderService(restClient, tokenService, false);
		return new AtrOcoManagementService(holdingsService, atrService, conditionalOrderService, (key, message) -> {},
			new TradingHaltSwitch("build/tmp/test-no-halt-file", (key, message) -> {}));
	}

	private static final String ACK_JSON = """
		{"type":"subscriptions","id":null,"subscribed":["personal:order:3"],"rejected":[]}
		""";
	private static final String REJECTED_ACK_JSON = """
		{"type":"subscriptions","id":null,"subscribed":[],"rejected":[{"topic":"personal:order:3","reason":"denied"}]}
		""";

	// 연결 완료(재동기화와 PING 시작)는 웹소켓이 열린 시점이 아니라 구독이 확정된 시점이어야 한다
	@Test
	void connectionIsCompleteOnlyWhenTheSubscriptionIsConfirmed() throws Exception {
		int[] connected = {0};
		OrderEventWebSocketHandler handler = new OrderEventWebSocketHandler(3L, new ObjectMapper(),
			new TradeRecordWriter("build/tmp/test-trades-noop.csv"), newUnreachableAtrOcoManagementService(),
			(key, message) -> {}, () -> connected[0]++, () -> {});

		handler.handleTextMessage(null, new TextMessage(REJECTED_ACK_JSON));
		assertEquals(0, connected[0]);

		handler.handleTextMessage(null, new TextMessage(ACK_JSON));
		assertEquals(1, connected[0]);
	}
}
