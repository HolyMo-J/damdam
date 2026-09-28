package com.damdam.bot.papertrading;

import java.util.List;

// 가상매매 대상 종목군 한 번 계산 결과. members는 거래대금 순위 오름차순이고, 자금이 모자랄 때
// "거래대금이 큰 순"으로 신호를 고르는 규칙(docs/strategy.md 공통 규칙)에 그대로 쓰라고 순위를 함께 담는다
public record TargetUniverse(List<Member> members, List<Exclusion> exclusions) {

	public record Member(int rank, String symbol, String name, String tradingAmount) {
	}

	public record Exclusion(int rank, String symbol, ExclusionReason reason) {
	}
}
