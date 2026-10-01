package com.damdam.bot.papertrading;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

// 전략별 총 노출 한도 (보유 중인 포지션과 포기한 포지션의 매수 금액 합계 기준. 포기한 포지션은 사람이 상태 파일을 고치기 전까지 영구히 포함한다). 동시 보유 종목 수에는 한도를 두지 않는다 (2026-09-29 사용자 결정)
public final class ExposureLimit {

	public static final BigDecimal TOTAL_LIMIT = new BigDecimal("500000");

	private ExposureLimit() {
	}

	public static BigDecimal exposureOf(Collection<PaperPosition> positions) {
		return positions.stream().map(PaperPosition::exposure).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	// 남은 한도와 같은 금액까지는 살 수 있다 ("남은 한도보다 크면 건너뛴다")
	public static Optional<SkipReason> blocker(BigDecimal price, BigDecimal currentExposure) {
		if (price.compareTo(TOTAL_LIMIT) > 0) {
			return Optional.of(SkipReason.PRICE_EXCEEDS_LIMIT);
		}
		if (currentExposure.add(price).compareTo(TOTAL_LIMIT) > 0) {
			return Optional.of(SkipReason.EXPOSURE_LIMIT);
		}
		return Optional.empty();
	}
}
