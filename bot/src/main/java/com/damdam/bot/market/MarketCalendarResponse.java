package com.damdam.bot.market;

// GET /api/v1/market-calendar/KR 응답. integrated가 null이면 그 날짜는 휴장일이다.
// 장중 세션 시간(preMarket/regularMarket/afterMarket)은 지금 쓰지 않아 담지 않는다.
record MarketCalendarResponse(MarketCalendarResult result) {
}
