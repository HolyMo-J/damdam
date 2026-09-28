package com.damdam.bot.stocks;

// koreanMarketDetail은 국내 종목(KOSPI, KOSDAQ, KR_ETC)에만 오고 해외 종목은 null이다
public record StockInfo(String symbol, String name, String market, String securityType, boolean isCommonShare,
						String status, KoreanMarketDetail koreanMarketDetail) {

	// nxtTradingSuspended는 NXT 미지원 종목이면 null이라 Boolean으로 받는다
	public record KoreanMarketDetail(boolean liquidationTrading, boolean nxtSupported, boolean krxTradingSuspended,
									 Boolean nxtTradingSuspended) {
	}
}
