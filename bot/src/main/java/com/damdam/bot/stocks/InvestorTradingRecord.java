package com.damdam.bot.stocks;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

// 투자자별 매매동향 일별 기록에서 가상매매가 쓰는 값만 뽑은 것. 기관 합계 순매수 거래량(주)이고 매도 우위면 음수다.
// updatedAt은 기록 전체의 마지막 갱신 시각이다. 당일 기록이 잠정치인지 확정치인지 이 값으로 가릴 수 있는지는 확인하지 못했지만
// (docs/strategy.md "전략 A"), 신호가 어느 시점의 값으로 났는지 나중에 다시 보고 관찰할 수 있게 함께 담는다. 명세상 필수지만 판정에는 쓰지 않으므로 없어도 실패로 다루지 않는다
public record InvestorTradingRecord(LocalDate date, BigDecimal institutionNetBuyVolume, Instant updatedAt) {
}
