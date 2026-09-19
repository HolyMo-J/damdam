package com.damdam.bot.backtest;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.CandlePageResponse;
import com.damdam.bot.market.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 과거 일봉 데이터를 최대한 과거까지 페이지네이션으로 모아 CSV로 저장한다 (2단계 백테스트 데이터 수집용)
@Service
public class CandleHistoryExporter {

	private static final Logger log = LoggerFactory.getLogger(CandleHistoryExporter.class);
	private static final int PAGE_SIZE = 200;
	private static final int MAX_PAGES = 50; // 최대 10,000봉 (일봉 기준 약 40년, 안전 상한)
	private static final long DEFAULT_RETRY_AFTER_MS = 2000;

	private final MarketDataService marketDataService;

	public CandleHistoryExporter(MarketDataService marketDataService) {
		this.marketDataService = marketDataService;
	}

	// 반환값: 수집한 캔들 개수. 수정주가(adjusted=true) 기준
	public int exportDailyHistory(String symbol, Path outputCsv) {
		return exportDailyHistory(symbol, outputCsv, true);
	}

	// adjusted=false를 넘기면 원본 시세를 수집한다 (권리락/배당락/액면분할 감지용)
	public int exportDailyHistory(String symbol, Path outputCsv, boolean adjusted) {
		List<Candle> all = new ArrayList<>();
		String before = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			CandlePageResponse response = fetchPageWithRetry(symbol, before, adjusted);
			if (response == null || response.candles().isEmpty()) {
				break;
			}
			all.addAll(response.candles());
			log.info("[캔들 수집] {} {}페이지: {}개 (누적 {}개), 가장 오래된 봉: {}",
				symbol, page + 1, response.candles().size(), all.size(),
				response.candles().get(response.candles().size() - 1).timestamp());

			if (response.nextBefore() == null) {
				log.info("[캔들 수집] {} 더 이상 과거 봉이 없습니다 (마지막 페이지).", symbol);
				break;
			}
			before = response.nextBefore();
		}

		Collections.reverse(all); // 오래된 것부터 오름차순으로 저장 (분석 편의)
		writeCsv(symbol, all, outputCsv);
		return all.size();
	}

	private CandlePageResponse fetchPageWithRetry(String symbol, String before, boolean adjusted) {
		try {
			return marketDataService.getDailyCandlesPage(symbol, PAGE_SIZE, before, adjusted);
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() != 429) {
				throw e;
			}
			long waitMs = readRetryAfterMs(e).orElse(DEFAULT_RETRY_AFTER_MS);
			log.warn("[캔들 수집] {} 호출 제한(429). {}ms 대기 후 재시도합니다.", symbol, waitMs);
			sleep(waitMs);
			return marketDataService.getDailyCandlesPage(symbol, PAGE_SIZE, before, adjusted);
		}
	}

	private java.util.Optional<Long> readRetryAfterMs(RestClientResponseException e) {
		HttpHeaders headers = e.getResponseHeaders();
		if (headers == null) {
			return java.util.Optional.empty();
		}
		String retryAfter = headers.getFirst("Retry-After");
		if (retryAfter == null) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(Long.parseLong(retryAfter.trim()) * 1000);
		} catch (NumberFormatException ignored) {
			return java.util.Optional.empty();
		}
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void writeCsv(String symbol, List<Candle> candles, Path outputCsv) {
		try {
			Path parent = outputCsv.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			StringBuilder sb = new StringBuilder("timestamp,open,high,low,close,volume,currency\n");
			for (Candle c : candles) {
				sb.append(c.timestamp()).append(',')
					.append(c.openPrice()).append(',')
					.append(c.highPrice()).append(',')
					.append(c.lowPrice()).append(',')
					.append(c.closePrice()).append(',')
					.append(c.volume()).append(',')
					.append(c.currency()).append('\n');
			}
			Files.writeString(outputCsv, sb.toString());
			log.info("[캔들 수집] {} 저장 완료: {} ({}행)", symbol, outputCsv, candles.size());
		} catch (IOException e) {
			log.warn("[캔들 수집] {} CSV 저장 실패: {}", symbol, e.getMessage());
		}
	}
}
