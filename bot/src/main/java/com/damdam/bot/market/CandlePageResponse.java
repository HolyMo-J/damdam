package com.damdam.bot.market;

import java.util.List;

record CandlePageResponse(List<Candle> candles, String nextBefore) {
}
