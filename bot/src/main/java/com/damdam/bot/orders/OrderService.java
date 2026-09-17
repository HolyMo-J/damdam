package com.damdam.bot.orders;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

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
}
