package com.damdam.bot.orderevent;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.orders.OrderService;
import com.damdam.bot.records.TradeRecordWriter;
import com.damdam.bot.token.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

// 연결이 끊기면 지수 백오프로 재연결하고, 재연결마다 REST 주문 목록으로 재동기화한다
@Service
public class OrderStreamClient {

	private static final Logger log = LoggerFactory.getLogger(OrderStreamClient.class);
	private static final URI ENDPOINT = URI.create("wss://openapi-ws.tossinvest.com/ws/v1");
	private static final long MAX_BACKOFF_SECONDS = 30;
	private static final long PING_INTERVAL_SECONDS = 60;

	private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(OrderStreamClient::newDaemonThread);

	private final TokenService tokenService;
	private final OrderService orderService;
	private final ObjectMapper objectMapper;
	private final TradeRecordWriter tradeRecordWriter;
	private final AtrOcoManagementService atrOcoManagementService;

	private volatile boolean running;
	private volatile WebSocketSession currentSession;
	private volatile ScheduledFuture<?> pingTask;
	private long accountSeq;
	private int reconnectAttempts;

	public OrderStreamClient(TokenService tokenService, OrderService orderService, ObjectMapper objectMapper,
			TradeRecordWriter tradeRecordWriter, AtrOcoManagementService atrOcoManagementService) {
		this.tokenService = tokenService;
		this.orderService = orderService;
		this.objectMapper = objectMapper;
		this.tradeRecordWriter = tradeRecordWriter;
		this.atrOcoManagementService = atrOcoManagementService;
	}

	public void start(long accountSeq) {
		this.accountSeq = accountSeq;
		this.running = true;
		connect();
	}

	public void stop() {
		running = false;
		scheduler.shutdownNow();
		closeQuietly(currentSession);
	}

	private void connect() {
		if (!running) {
			return;
		}
		// 재연결 전 이전 연결을 먼저 닫아야 새 연결이 밀려나지 않는다
		closeQuietly(currentSession);

		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken());
		var handler = new OrderEventWebSocketHandler(accountSeq, objectMapper, tradeRecordWriter, atrOcoManagementService,
			this::onConnected, this::onDisconnected);

		webSocketClient.execute(handler, headers, ENDPOINT)
			.whenComplete((session, error) -> {
				if (error != null) {
					log.warn("웹소켓 연결 실패: {}", error.getMessage());
					scheduleReconnect();
					return;
				}
				currentSession = session;
			});
	}

	private void onConnected() {
		reconnectAttempts = 0;
		pingTask = scheduler.scheduleAtFixedRate(this::sendPing, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private void onDisconnected() {
		if (pingTask != null) {
			pingTask.cancel(false);
		}
		if (!running) {
			return;
		}
		resync();
		scheduleReconnect();
	}

	private void sendPing() {
		WebSocketSession session = currentSession;
		if (session == null || !session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(new TextMessage("PING"));
		} catch (IOException e) {
			log.warn("PING 전송 실패: {}", e.getMessage());
		}
	}

	// 끊긴 구간의 이벤트는 다시 전달되지 않으므로, 진행중 주문 목록으로 상태를 다시 맞춘다
	private void resync() {
		try {
			var openOrders = orderService.getOpenOrders(accountSeq);
			log.info("주문 목록 재동기화: 진행중 주문 {}건", openOrders.size());
		} catch (Exception e) {
			log.warn("주문 목록 재동기화 실패: {}", e.getMessage());
		}
	}

	private void scheduleReconnect() {
		if (!running) {
			return;
		}
		long delaySeconds = Math.min(1L << Math.min(reconnectAttempts, 5), MAX_BACKOFF_SECONDS);
		reconnectAttempts++;
		long jitterMillis = ThreadLocalRandom.current().nextLong(0, 500);
		log.info("{}초 뒤 재연결합니다.", delaySeconds);
		scheduler.schedule(this::connect, delaySeconds * 1000 + jitterMillis, TimeUnit.MILLISECONDS);
	}

	private void closeQuietly(WebSocketSession session) {
		if (session != null && session.isOpen()) {
			try {
				session.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static Thread newDaemonThread(Runnable r) {
		Thread thread = new Thread(r, "order-stream-reconnect");
		thread.setDaemon(true);
		return thread;
	}
}
