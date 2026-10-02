package com.damdam.bot.papertrader;

import java.util.List;

// 가상매매 원장이 전략을 구분하는 이름 (docs/strategy.md "전략 후보"). 원장은 영숫자 16자 이하만 받는다
final class Strategies {

	static final String INSTITUTION = "A";   // 전략 A: 기관 순매수 추종
	static final String ICHIMOKU = "B";      // 전략 B: 일목 구름대 상향 돌파와 거래량 급증
	static final List<String> ALL = List.of(INSTITUTION, ICHIMOKU);

	private Strategies() {
	}
}
