package com.damdam.bot.papertrading;

import com.damdam.bot.stocks.StockInfo;
import com.damdam.bot.stocks.StockWarning;

import java.util.List;
import java.util.Optional;
import java.util.Set;

// 대상 종목군 제외 규칙. HTTP 없이 입력만 보고 판단하는 순수 계산이다 (docs/strategy.md "대상 종목군").
// 모르는 것은 통과시키지 않는 쪽(fail-closed)으로 판정한다. 스팩, 순수 관리종목, 단기과열, VI는 걸러내지 않는다
final class UniverseFilter {

	private static final Set<String> KR_MARKETS = Set.of("KOSPI", "KOSDAQ");

	private UniverseFilter() {
	}

	// 종목 정보만으로 판정. 통과하면 빈 Optional
	static Optional<ExclusionReason> checkInfo(StockInfo info) {
		if (info == null) {
			return Optional.of(ExclusionReason.INFO_MISSING);
		}
		if (!"STOCK".equals(info.securityType()) || !info.isCommonShare()) {
			return Optional.of(ExclusionReason.NOT_ORDINARY_STOCK);
		}
		if (!"ACTIVE".equals(info.status())) {
			return Optional.of(ExclusionReason.NOT_ACTIVE);
		}
		if (!KR_MARKETS.contains(info.market())) {
			return Optional.of(ExclusionReason.NOT_KOSPI_KOSDAQ);
		}
		StockInfo.KoreanMarketDetail detail = info.koreanMarketDetail();
		if (detail == null) {
			return Optional.of(ExclusionReason.KOREAN_DETAIL_MISSING);
		}
		if (detail.liquidationTrading()) {
			return Optional.of(ExclusionReason.LIQUIDATION_TRADING);
		}
		if (detail.krxTradingSuspended()) {
			return Optional.of(ExclusionReason.TRADING_SUSPENDED);
		}
		return Optional.empty();
	}

	// 활성 경고 목록으로 판정. 정리매매 경고는 종목 정보의 정리매매 플래그와 겹치지만 이중 안전장치로 함께 본다.
	// 모르는 warningType(단기과열, VI 등)은 무시한다
	static Optional<ExclusionReason> checkWarnings(List<StockWarning> warnings) {
		if (hasWarning(warnings, "LIQUIDATION_TRADING")) {
			return Optional.of(ExclusionReason.LIQUIDATION_TRADING);
		}
		if (hasWarning(warnings, "INVESTMENT_RISK")) {
			return Optional.of(ExclusionReason.INVESTMENT_RISK);
		}
		if (hasWarning(warnings, "INVESTMENT_WARNING")) {
			return Optional.of(ExclusionReason.INVESTMENT_WARNING);
		}
		return Optional.empty();
	}

	private static boolean hasWarning(List<StockWarning> warnings, String type) {
		return warnings.stream().anyMatch(w -> type.equals(w.warningType()));
	}
}
