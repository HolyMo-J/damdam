package com.damdam.bot.conditionalorder;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtrOcoPricingTest {

	@Test
	void krwRoundsToWholeWonAndStepsDownOneWonForStopLossOrderPrice() {
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(new BigDecimal("71000"), new BigDecimal("1234.5678"), true);

		assertEquals("72235", prices.takeProfitTrigger());
		assertEquals("72235", prices.takeProfitOrderPrice());
		assertEquals("69765", prices.stopLossTrigger());
		assertEquals("69764", prices.stopLossOrderPrice());
	}

	@Test
	void usdAboveOneDollarTruncatesToTwoDecimalsAndStepsDownOneCent() {
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(new BigDecimal("155.74"), new BigDecimal("6.5055"), false);

		assertEquals("162.24", prices.takeProfitTrigger());
		assertEquals("162.24", prices.takeProfitOrderPrice());
		assertEquals("149.23", prices.stopLossTrigger());
		assertEquals("149.22", prices.stopLossOrderPrice());
	}

	@Test
	void usdBelowOneDollarTruncatesToFourDecimals() {
		AtrOcoPricing.Prices prices = AtrOcoPricing.calculate(new BigDecimal("0.5"), new BigDecimal("0.05"), false);

		assertEquals("0.5500", prices.takeProfitTrigger());
		assertEquals("0.4500", prices.stopLossTrigger());
		assertEquals("0.4499", prices.stopLossOrderPrice());
	}
}
