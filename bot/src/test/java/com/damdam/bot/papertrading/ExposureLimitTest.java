package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static com.damdam.bot.papertrading.PaperTestSupport.position;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExposureLimitTest {

	@Test
	void allowsBuyingUpToExactlyTheRemainingLimit() {
		assertEquals(Optional.empty(), ExposureLimit.blocker(bd("100000"), bd("400000")));
		assertEquals(Optional.empty(), ExposureLimit.blocker(bd("500000"), bd("0")));
	}

	@Test
	void blocksWhenThePriceExceedsTheRemainingLimit() {
		assertEquals(Optional.of(SkipReason.EXPOSURE_LIMIT), ExposureLimit.blocker(bd("100001"), bd("400000")));
	}

	@Test
	void distinguishesAPriceLargerThanTheWholeLimit() {
		assertEquals(Optional.of(SkipReason.PRICE_EXCEEDS_LIMIT), ExposureLimit.blocker(bd("500001"), bd("0")));
		assertEquals(Optional.of(SkipReason.PRICE_EXCEEDS_LIMIT), ExposureLimit.blocker(bd("500001"), bd("300000")));
	}

	@Test
	void exposureIsTheSumOfEntryPricesTimesQuantity() {
		List<PaperPosition> positions = List.of(position("A", "200000", "1000"), position("B", "150000", "1000"));

		assertEquals(0, bd("350000").compareTo(ExposureLimit.exposureOf(positions)));
		assertEquals(0, bd("0").compareTo(ExposureLimit.exposureOf(List.of())));
	}
}
