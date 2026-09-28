package com.damdam.bot.stocks;

// 매수 유의사항 한 건. 명세가 "unknown code를 허용하라"고 해서 warningType은 enum이 아니라 문자열로 받는다.
// 날짜는 YYYY-MM-DD 문자열이고, 시작일 미정이나 진행 중이면 null이다
public record StockWarning(String warningType, String exchange, String startDate, String endDate) {
}
