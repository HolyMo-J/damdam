package com.damdam.bot.market;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// docs/strategy.md v0: 익절/손절 기준값으로 쓰는 최근 14거래일 ATR
@Service
public class AtrService {

	private static final int ATR_PERIOD = 14;

	private final MarketDataService marketDataService;

	public AtrService(MarketDataService marketDataService) {
		this.marketDataService = marketDataService;
	}

	public BigDecimal getAtr14(String symbol) {
		List<Candle> candles = marketDataService.getDailyCandles(symbol, ATR_PERIOD + 1);
		return AverageTrueRange.calculate(candles, ATR_PERIOD);
	}
}
