package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.damdam.bot.papertrading.PaperTestSupport.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperCostsTest {

	@Test
	void netProfitAppliesFeesOnBothSidesAndTaxOnSell() {
		// 매도 10100 x (1 - 0.00015 - 0.002) = 10100 x 0.99785 = 10078.285, 매수 10000 x 1.00015 = 10001.5
		assertEquals(0, bd("76.785").compareTo(PaperCosts.netProfit(bd("10000"), bd("10100"), 1)));
	}

	@Test
	void netProfitScalesWithQuantity() {
		assertEquals(0, bd("153.570").compareTo(PaperCosts.netProfit(bd("10000"), bd("10100"), 2)));
	}

	@Test
	void flatTradeIsANetLossOfCosts() {
		// 같은 가격에 사고 팔아도 9978.5 - 10001.5 = -23.0
		assertEquals(0, bd("-23.0").compareTo(PaperCosts.netProfit(bd("10000"), bd("10000"), 1)));
	}

	@Test
	void netReturnIsSellNetOverBuyGrossMinusOne() {
		// 10078.285 / 10001.5 - 1 = 0.00767735...
		BigDecimal actual = PaperCosts.netReturn(bd("10000"), bd("10100"));
		assertEquals(0, bd("0.007677").compareTo(actual.setScale(6, java.math.RoundingMode.HALF_UP)));
	}

	@Test
	void netReturnIsNegativeForALoss() {
		// 9900 x 0.99785 = 9878.715, 9878.715 / 10001.5 - 1 = -0.012277...
		BigDecimal actual = PaperCosts.netReturn(bd("10000"), bd("9900"));
		assertEquals(0, bd("-0.012277").compareTo(actual.setScale(6, java.math.RoundingMode.HALF_UP)));
	}
}
