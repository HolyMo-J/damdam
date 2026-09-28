package com.damdam.bot.papertrading;

// 대상 종목군에서 빠진 이유. 한 종목에는 처음 걸린 사유 하나만 붙는다
public enum ExclusionReason {
	INFO_MISSING,               // 종목 정보 응답에 없음 (모르면 통과시키지 않는다)
	NOT_ORDINARY_STOCK,         // ETF, ETN, REIT 등이거나 우선주
	NOT_ACTIVE,                 // 상장 상태가 ACTIVE가 아님
	NOT_KOSPI_KOSDAQ,           // 코스피, 코스닥 밖
	KOREAN_DETAIL_MISSING,      // 국내 상세 정보(정리매매, 거래정지)를 못 받음
	LIQUIDATION_TRADING,        // 정리매매
	TRADING_SUSPENDED,          // KRX 거래정지
	INVESTMENT_WARNING,         // 투자경고 지정
	INVESTMENT_RISK,            // 투자위험 지정
	WARNING_LOOKUP_FAILED       // 경고 조회가 끝내 실패
}
