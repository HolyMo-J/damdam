package com.damdam.bot.stocks;

import java.time.LocalDate;
import java.util.List;

// 투자자별 매매동향 한 페이지. records는 최신순이다. nextUntil은 다음 페이지를 부를 때 until로 그대로 넘기는 값이고,
// 더 이상 데이터가 없으면 null이다 (명세). 과거 보관 기간을 재는 조회 러너가 쓴다
public record InvestorTradingPage(List<InvestorTradingRecord> records, LocalDate nextUntil) {
}
