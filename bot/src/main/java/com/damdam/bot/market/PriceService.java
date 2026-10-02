package com.damdam.bot.market;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.regex.Pattern;

// 현재가 조회 (GET /api/v1/prices, MARKET_DATA 그룹 초당 15회). 한 번 호출에 종목을 최대 200개까지 콤마로 묶어 보낸다.
// 조회 전용이고 주문 경로와 관계없다. 명세 예시는 국내(005930,000660)와 해외(AAPL,MSFT)가 따로 있고, 한 호출에 섞어도 되는 것은 실측으로 확인했다 (2026-10-03).
// 해외 시세가 실시간인지 지연인지는 명세에 없다. 응답의 timestamp와 조회 시각의 차이로 보고, 미국 정규장 중 1~3초였다 (docs/measurements.md "현재가 조회")
@Service
public class PriceService {

	static final int MAX_SYMBOLS = 200;
	// 명세: 영문 대소문자, 숫자, '.', '-'만 허용한다. 호출 전에 걸러서 잘못된 입력이 서버까지 가지 않게 한다
	private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.\\-]+");

	private final RestClient restClient;
	private final TokenService tokenService;

	public PriceService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// 응답 순서는 서버가 정한다. 요청한 심볼이 응답에 빠질 수 있어서(없는 종목 등) 호출하는 쪽이 대조해야 한다
	public List<StockPrice> getPrices(List<String> symbols) {
		validate(symbols);
		var uri = UriComponentsBuilder.fromPath("/api/v1/prices")
			.queryParam("symbols", String.join(",", symbols))
			.build().toUri();
		PricesResponse response;
		try {
			response = restClient.get()
				.uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
				.retrieve()
				.body(PricesResponse.class);
		} catch (RestClientResponseException e) {
			// 응답 본문은 메시지에 넣지 않는다 (상태 코드만). 429는 Retry-After를 기다리지 않고 실패로 알린다
			throw new IllegalStateException("현재가 조회 실패: HTTP " + e.getStatusCode().value(), e);
		} catch (RestClientException e) {
			throw new IllegalStateException("현재가 조회 실패: 요청 오류", e);
		}
		if (response == null || response.result() == null) {
			throw new IllegalStateException("현재가 조회 실패: 응답이 비어 있습니다");
		}
		return response.result();
	}

	private static void validate(List<String> symbols) {
		if (symbols == null || symbols.isEmpty()) {
			throw new IllegalArgumentException("조회할 심볼이 없습니다");
		}
		if (symbols.size() > MAX_SYMBOLS) {
			throw new IllegalArgumentException("심볼은 한 번에 최대 " + MAX_SYMBOLS + "개까지 조회할 수 있습니다 (요청 " + symbols.size() + "개)");
		}
		for (String symbol : symbols) {
			if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
				throw new IllegalArgumentException("심볼 형식이 올바르지 않습니다: " + symbol);
			}
		}
	}
}
