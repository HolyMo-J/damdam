package com.damdam.bot.papertrading;

// 대기 신호를 진입하지 못한 사유. 조용히 사라지지 않게 사유별로 기록한다 (docs/records.md)
public enum SkipReason {
	// 진입 봉 거래량이 0 (거래정지일은 "거래량 0, 가격은 직전 종가"로 들어와 없는 가격으로 사게 되는 것을 막는다)
	ZERO_VOLUME,
	// 진입가 - ATR이 0 이하이거나 ATR이 너무 작아 익절가나 손절가가 진입가와 같아지는 경우
	INVALID_EXIT_PRICES,
	// 1주 가격이 총 노출 한도(50만원) 전체보다 큼. 한도가 비어도 영원히 살 수 없다
	PRICE_EXCEEDS_LIMIT,
	// 1주 가격이 남은 총 노출 한도보다 큼
	EXPOSURE_LIMIT,
	// 그 주 실현 손실이 주간 한도를 넘어 새 매수를 멈춘 주
	WEEKLY_HALT,
	// 같은 종목을 이미 보유 중 (한 번에 한 포지션만, 청산한 날 같은 종목 재진입 금지)
	ALREADY_HELD
}
