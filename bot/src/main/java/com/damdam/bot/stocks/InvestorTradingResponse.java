package com.damdam.bot.stocks;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// GET /api/v1/stocks/{symbol}/investor-trading 응답 중 필요한 부분만 받는다. 나머지 필드(개인, 외국인, 기관 세부 등)는 무시한다
record InvestorTradingResponse(Result result) {

	record Result(List<Row> records) {
	}

	record Row(LocalDate date, Instant updatedAt, Institution institution) {
	}

	record Institution(BigDecimal netBuyVolume) {
	}
}
