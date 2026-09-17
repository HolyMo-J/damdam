package com.damdam.bot.orderevent;

import com.damdam.bot.records.TradeRecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

class OrderEventWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(OrderEventWebSocketHandler.class);

	private final long accountSeq;
	private final ObjectMapper objectMapper;
	private final TradeRecordWriter tradeRecordWriter;
	private final Runnable onConnected;
	private final Runnable onDisconnected;

	OrderEventWebSocketHandler(long accountSeq, ObjectMapper objectMapper, TradeRecordWriter tradeRecordWriter,
			Runnable onConnected, Runnable onDisconnected) {
		this.accountSeq = accountSeq;
		this.objectMapper = objectMapper;
		this.tradeRecordWriter = tradeRecordWriter;
		this.onConnected = onConnected;
		this.onDisconnected = onDisconnected;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		log.info("웹소켓 연결됨. 계좌 {} 주문 이벤트 구독을 선언합니다.", accountSeq);
		var declare = List.of(new OrderSubscribeDeclare("personal:order", List.of(String.valueOf(accountSeq))));
		session.sendMessage(new TextMessage(objectMapper.writeValueAsString(declare)));
		onConnected.run();
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		JsonNode node = objectMapper.readTree(message.getPayload());
		String type = node.path("type").asString();
		switch (type) {
			case "subscriptions" -> handleAck(node);
			case "message" -> handleOrderEvent(node);
			case "error" -> handleError(node);
			case "pong" -> log.debug("pong 수신");
			default -> log.warn("알 수 없는 프레임 타입: {}", type);
		}
	}

	private void handleAck(JsonNode node) {
		SubscriptionAck ack = objectMapper.treeToValue(node, SubscriptionAck.class);
		log.info("구독 확정: {}, 거부: {}", ack.subscribed(), ack.rejected());
	}

	private void handleOrderEvent(JsonNode node) {
		OrderEventFrame frame = objectMapper.treeToValue(node, OrderEventFrame.class);
		String event = frame.data().event();
		var order = frame.data().order();
		log.info("주문 이벤트 [{}] {} {} 수량 {}, 상태: {}, 체결수량: {}",
			event, order.symbol(), order.side(), order.quantity(),
			order.status(), order.execution().filledQuantity());

		if ("FILL".equals(event) || "PARTIAL_FILL".equals(event)) {
			var execution = order.execution();
			tradeRecordWriter.record(order.orderId(), order.symbol(), order.side(), event,
				execution.filledQuantity(), execution.averageFilledPrice(), execution.filledAmount(),
				execution.commission(), execution.tax(), order.currency(), order.orderType(), order.status());
		}
	}

	private void handleError(JsonNode node) {
		ErrorFrame error = objectMapper.treeToValue(node, ErrorFrame.class);
		log.warn("웹소켓 에러: {} - {}", error.error().code(), error.error().message());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		log.warn("웹소켓 연결 종료: {}", status);
		onDisconnected.run();
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.warn("웹소켓 전송 오류: {}", exception.getMessage());
	}
}
