package com.damdam.bot.orders;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

	private static final String ACCOUNT_HEADER = "X-Tossinvest-Account";

	private final RestClient restClient;
	private final TokenService tokenService;

	public OrderService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// 진행중 주문(OPEN)은 페이징 없이 전량 반환된다. 재연결 후 재동기화 용도로 쓴다
	public List<Order> getOpenOrders(long accountSeq) {
		OrdersResponse response = restClient.get()
			.uri("/api/v1/orders?status=OPEN")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
			.retrieve()
			.body(OrdersResponse.class);

		return response == null ? List.of() : response.result().orders();
	}

	// 최근 lookbackDays 안의 종료된 주문을 종목 구분 없이 반환한다 (재동기화로 놓친 체결을 찾는 용도)
	public List<Order> getRecentClosedOrders(long accountSeq, int lookbackDays) {
		return getClosedOrders(accountSeq, null, lookbackDays);
	}

	// 최근 lookbackDays 안의 체결 완료 주문을 시간순으로 반환한다 (시간 청산 판단용). symbol이 null이면 전 종목
	public List<Order> getClosedOrders(long accountSeq, String symbol, int lookbackDays) {
		List<Order> orders = new ArrayList<>();
		String cursor = null;
		String from = LocalDate.now().minusDays(lookbackDays).toString();

		while (true) {
			String uri = UriComponentsBuilder.fromPath("/api/v1/orders")
				.queryParam("status", "CLOSED")
				.queryParamIfPresent("symbol", Optional.ofNullable(symbol))
				.queryParam("from", from)
				.queryParam("limit", 100)
				.queryParamIfPresent("cursor", Optional.ofNullable(cursor))
				.toUriString();

			OrdersResponse response = restClient.get()
				.uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.header(ACCOUNT_HEADER, String.valueOf(accountSeq))
				.retrieve()
				.body(OrdersResponse.class);

			if (response == null) {
				break;
			}
			orders.addAll(response.result().orders());
			if (!response.result().hasNext()) {
				break;
			}
			cursor = response.result().nextCursor();
		}

		orders.sort(Comparator.comparing(this::fillOrOrderTime));
		return orders;
	}

	private OffsetDateTime fillOrOrderTime(Order order) {
		String filledAt = order.execution().filledAt();
		return OffsetDateTime.parse(filledAt != null ? filledAt : order.orderedAt());
	}
}
