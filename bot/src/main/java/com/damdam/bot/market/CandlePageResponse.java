package com.damdam.bot.market;

import java.util.List;

public record CandlePageResponse(List<Candle> candles, String nextBefore) {
}
