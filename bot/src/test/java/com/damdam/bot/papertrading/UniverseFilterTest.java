package com.damdam.bot.papertrading;

import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockWarning;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniverseFilterTest {

	private static StockInfo.KoreanMarketDetail detail(boolean liquidation, boolean suspended) {
		return new StockInfo.KoreanMarketDetail(liquidation, true, suspended, false);
	}

	// 모든 조건을 통과하는 기준 종목에서 각 테스트가 한 필드만 바꿔 그 조건만 시험한다
	private static StockInfo info(String market, String securityType, boolean common, String status,
									StockInfo.KoreanMarketDetail detail) {
		return new StockInfo("005930", "삼성전자", market, securityType, common, status, detail);
	}

	private static StockInfo ok() {
		return info("KOSPI", "STOCK", true, "ACTIVE", detail(false, false));
	}

	private static StockWarning warning(String type) {
		return new StockWarning(type, "KRX", "2026-09-01", null);
	}

	@Test
	void ordinaryActiveStockPasses() {
		assertEquals(Optional.empty(), UniverseFilter.checkInfo(ok()));
		assertEquals(Optional.empty(), UniverseFilter.checkInfo(info("KOSDAQ", "STOCK", true, "ACTIVE", detail(false, false))));
	}

	@Test
	void missingInfoIsExcluded() {
		assertEquals(Optional.of(ExclusionReason.INFO_MISSING), UniverseFilter.checkInfo(null));
	}

	@Test
	void etfEtnReitAndPreferredSharesAreExcluded() {
		for (String type : List.of("ETF", "ETN", "REIT", "STOCK_WARRANTS", "FOREIGN_STOCK")) {
			assertEquals(Optional.of(ExclusionReason.NOT_ORDINARY_STOCK),
				UniverseFilter.checkInfo(info("KOSPI", type, true, "ACTIVE", detail(false, false))), type);
		}
		assertEquals(Optional.of(ExclusionReason.NOT_ORDINARY_STOCK),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", false, "ACTIVE", detail(false, false))));
	}

	@Test
	void nonActiveStatusIsExcluded() {
		assertEquals(Optional.of(ExclusionReason.NOT_ACTIVE),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "DELISTED", detail(false, false))));
		assertEquals(Optional.of(ExclusionReason.NOT_ACTIVE),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "SCHEDULED", detail(false, false))));
	}

	@Test
	void marketsOtherThanKospiAndKosdaqAreExcluded() {
		for (String market : List.of("KR_ETC", "NASDAQ", "NYSE", "US_ETC", "UNKNOWN_FUTURE_VALUE")) {
			assertEquals(Optional.of(ExclusionReason.NOT_KOSPI_KOSDAQ),
				UniverseFilter.checkInfo(info(market, "STOCK", true, "ACTIVE", detail(false, false))), market);
		}
	}

	@Test
	void missingKoreanDetailIsExcludedInsteadOfAssumedSafe() {
		assertEquals(Optional.of(ExclusionReason.KOREAN_DETAIL_MISSING),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "ACTIVE", null)));
	}

	@Test
	void liquidationTradingAndSuspensionAreExcluded() {
		assertEquals(Optional.of(ExclusionReason.LIQUIDATION_TRADING),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "ACTIVE", detail(true, false))));
		assertEquals(Optional.of(ExclusionReason.TRADING_SUSPENDED),
			UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "ACTIVE", detail(false, true))));
	}

	@Test
	void nxtSuspensionAloneDoesNotExclude() {
		// 기준은 KRX 거래정지다. NXT 정지 여부는 보지 않는다
		StockInfo.KoreanMarketDetail nxtOnly = new StockInfo.KoreanMarketDetail(false, true, false, true);
		assertEquals(Optional.empty(), UniverseFilter.checkInfo(info("KOSPI", "STOCK", true, "ACTIVE", nxtOnly)));
	}

	@Test
	void warningsExcludeOnlyInvestmentWarningRiskAndLiquidation() {
		assertEquals(Optional.of(ExclusionReason.INVESTMENT_WARNING), UniverseFilter.checkWarnings(List.of(warning("INVESTMENT_WARNING"))));
		assertEquals(Optional.of(ExclusionReason.INVESTMENT_RISK), UniverseFilter.checkWarnings(List.of(warning("INVESTMENT_RISK"))));
		assertEquals(Optional.of(ExclusionReason.LIQUIDATION_TRADING), UniverseFilter.checkWarnings(List.of(warning("LIQUIDATION_TRADING"))));
	}

	@Test
	void otherWarningTypesAndEmptyListPass() {
		assertEquals(Optional.empty(), UniverseFilter.checkWarnings(List.of()));
		// 단기과열, VI, 신주인수권, 처음 보는 코드는 제외 대상이 아니다
		assertEquals(Optional.empty(), UniverseFilter.checkWarnings(List.of(
			warning("OVERHEATED"), warning("VI_STATIC"), warning("VI_DYNAMIC"), warning("STOCK_WARRANTS"), warning("SOMETHING_NEW"))));
	}

	@Test
	void harmlessWarningsDoNotHideADangerousOne() {
		assertEquals(Optional.of(ExclusionReason.INVESTMENT_WARNING),
			UniverseFilter.checkWarnings(List.of(warning("VI_STATIC"), warning("OVERHEATED"), warning("INVESTMENT_WARNING"))));
	}

	@Test
	void riskTakesPriorityOverWarningWhenBothArePresent() {
		assertEquals(Optional.of(ExclusionReason.INVESTMENT_RISK),
			UniverseFilter.checkWarnings(List.of(warning("INVESTMENT_WARNING"), warning("INVESTMENT_RISK"))));
	}
}
