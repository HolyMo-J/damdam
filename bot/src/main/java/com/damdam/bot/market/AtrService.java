package com.damdam.bot.market;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// docs/strategy.md v0: 익절/손절 기준값으로 쓰는 최근 14거래일 ATR
@Service
public class AtrService {

	private static final int ATR_PERIOD = 14;
	// 오늘 날짜 봉이 섞여 있어도 걸러낸 뒤 ATR_PERIOD + 1개를 채울 수 있게 여유분 1개를 더 받는다
	private static final int FETCH_COUNT = ATR_PERIOD + 2;

	private final MarketDataService marketDataService;

	public AtrService(MarketDataService marketDataService) {
		this.marketDataService = marketDataService;
	}

	// 오늘 날짜 봉은 장중에는 아직 확정되지 않은 진행 중인 캔들일 수 있어 계산에서 제외한다.
	// 공식 문서(GET /api/v1/candles)에는 첫 봉이 진행 중인 캔들인지 명시돼 있지 않아, 확인 여부와 무관하게 방어적으로 걸러낸다
	public BigDecimal getAtr14(String symbol) {
		List<Candle> candles = marketDataService.getDailyCandles(symbol, FETCH_COUNT);
		LocalDate today = LocalDate.now();
		List<Candle> settledCandles = candles.stream()
			.filter(candle -> !OffsetDateTime.parse(candle.timestamp()).toLocalDate().isEqual(today))
			.toList();

		if (settledCandles.size() < ATR_PERIOD + 1) {
			throw new IllegalStateException(
				"ATR(%d) 계산에는 오늘 날짜 봉을 제외하고 캔들이 최소 %d개 필요한데 %d개만 있습니다. (전체 조회 %d개)"
					.formatted(ATR_PERIOD, ATR_PERIOD + 1, settledCandles.size(), candles.size()));
		}
		return AverageTrueRange.calculate(settledCandles.subList(0, ATR_PERIOD + 1), ATR_PERIOD);
	}
}
