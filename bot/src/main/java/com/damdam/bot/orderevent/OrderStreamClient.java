package com.damdam.bot.orderevent;

import com.damdam.bot.conditionalorder.AtrOcoManagementService;
import com.damdam.bot.notification.Notifier;
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
import java.util.concurrent.ExecutorService;
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
	// 연속 재연결 실패가 이 횟수에 도달하면 알린다 (일시적 끊김은 조용히 복구되게 두고, 계속 안 붙을 때만)
	private static final int ALERT_AFTER_FAILED_ATTEMPTS = 5;

	private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(OrderStreamClient::newDaemonThread);
	// 재동기화는 REST 호출이 여러 번 필요해서 PING과 재연결 스케줄을 막지 않도록 별도 스레드에서 실행한다
	private final ExecutorService resyncExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "order-stream-resync");
		thread.setDaemon(true);
		return thread;
	});

	private final TokenService tokenService;
	private final OrderResyncService orderResyncService;
	private final ObjectMapper objectMapper;
	private final TradeRecordWriter tradeRecordWriter;
	private final AtrOcoManagementService atrOcoManagementService;
	private final Notifier notifier;

	private volatile boolean running;
	private volatile WebSocketSession currentSession;
	private volatile ScheduledFuture<?> pingTask;
	private long accountSeq;
	private int reconnectAttempts;

	public OrderStreamClient(TokenService tokenService, OrderResyncService orderResyncService, ObjectMapper objectMapper,
			TradeRecordWriter tradeRecordWriter, AtrOcoManagementService atrOcoManagementService, Notifier notifier) {
		this.tokenService = tokenService;
		this.orderResyncService = orderResyncService;
		this.objectMapper = objectMapper;
		this.tradeRecordWriter = tradeRecordWriter;
		this.atrOcoManagementService = atrOcoManagementService;
		this.notifier = notifier;
	}

	public void start(long accountSeq) {
		this.accountSeq = accountSeq;
		this.running = true;
		connect();
	}

	public void stop() {
		running = false;
		scheduler.shutdownNow();
		resyncExecutor.shutdownNow();
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
			notifier, this::onConnected, this::onDisconnected);

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

	// 구독이 확정된 직후 호출된다 (봇 시작 때 첫 연결과 이후 모든 재연결 포함)
	private void onConnected() {
		reconnectAttempts = 0;
		if (pingTask != null) {
			pingTask.cancel(false);
		}
		pingTask = scheduler.scheduleAtFixedRate(this::sendPing, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
		// 끊긴 구간의 이벤트는 다시 전달되지 않으므로, 구독이 확정된 뒤에 REST로 상태를 다시 맞춘다
		resyncExecutor.execute(() -> orderResyncService.resync(accountSeq));
	}

	private void onDisconnected() {
		if (pingTask != null) {
			pingTask.cancel(false);
		}
		if (!running) {
			return;
		}
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

	private void scheduleReconnect() {
		if (!running) {
			return;
		}
		long delaySeconds = Math.min(1L << Math.min(reconnectAttempts, 5), MAX_BACKOFF_SECONDS);
		reconnectAttempts++;
		if (reconnectAttempts >= ALERT_AFTER_FAILED_ATTEMPTS) {
			notifier.send("ws-reconnect", "[담담] 주문 이벤트 웹소켓 재연결이 연속 " + reconnectAttempts
				+ "회 실패했습니다. 이 동안 체결 감지와 OCO 자동 등록이 멈춰 있습니다. 네트워크, 허용 IP, 토큰을 확인하세요.");
		}
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
