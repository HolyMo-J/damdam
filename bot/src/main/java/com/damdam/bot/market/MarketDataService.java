package com.damdam.bot.market;

import com.damdam.bot.token.TokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

@Service
public class MarketDataService {

	private final RestClient restClient;
	private final TokenService tokenService;

	public MarketDataService(RestClient tossRestClient, TokenService tokenService) {
		this.restClient = tossRestClient;
		this.tokenService = tokenService;
	}

	// 최신순(timestamp 내림차순)으로 반환한다. count는 최대 200. 항상 수정주가(adjusted=true) 기준
	public List<Candle> getDailyCandles(String symbol, int count) {
		return getDailyCandlesPage(symbol, count, null, true).candles();
	}

	// before를 지정하면 그 시각 이전(포함) 페이지를 반환한다. 과거로 계속 페이지네이션할 때 사용 (2단계 백테스트 데이터 수집용). 수정주가 기준(adjusted=true)
	public CandlePageResponse getDailyCandlesPage(String symbol, int count, String before) {
		return getDailyCandlesPage(symbol, count, before, true);
	}

	// adjusted=false로 원본 시세를 받을 수 있다 (권리락/배당락/액면분할 감지용, 2단계 백테스트 데이터 수집)
	public CandlePageResponse getDailyCandlesPage(String symbol, int count, String before, boolean adjusted) {
		var builder = UriComponentsBuilder.fromPath("/api/v1/candles")
			.queryParam("symbol", symbol)
			.queryParam("interval", "1d")
			.queryParam("count", count)
			.queryParam("adjusted", adjusted);
		if (before != null) {
			builder.queryParam("before", before);
		}

		// before 값에 타임존 오프셋(+)이 들어있어서 반드시 인코딩해야 한다 (안 하면 서버가 공백으로 오인)
		CandlesResponse response = restClient.get()
			.uri(builder.build().encode().toUri())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
			.retrieve()
			.body(CandlesResponse.class);

		return response == null ? new CandlePageResponse(List.of(), null) : response.result();
	}
}
