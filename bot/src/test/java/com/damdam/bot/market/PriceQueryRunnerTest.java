package com.damdam.bot.market;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceQueryRunnerTest {

	// 14:41:02.5 UTC. 미국 동부(-04:00) 10:41:00은 14:41:00 UTC라서 2.5초 차이다
	private static final Instant NOW = Instant.parse("2026-10-02T14:41:02.500Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void describeShowsTheDifferenceBetweenDataTimeAndQueryTimeAcrossOffsets() {
		StockPrice price = new StockPrice("SPCX", "2026-10-02T10:41:00.000-04:00", "148.12", "USD");

		String text = PriceQueryRunner.describe(price, NOW);

		assertTrue(text.startsWith("SPCX 148.12 USD"), text);
		assertTrue(text.contains("차이 2.5초"), text);
	}

	@Test
	void describeShowsAPastDataTimeAsALargeDifferenceSoDelayedQuotesAreVisible() {
		StockPrice price = new StockPrice("IREN", "2026-10-02T14:31:02.500Z", "60.5", "USD");

		assertTrue(PriceQueryRunner.describe(price, NOW).contains("차이 600.0초"));
	}

	@Test
	void describeHandlesMissingAndUnparsableTimestamps() {
		assertTrue(PriceQueryRunner.describe(new StockPrice("X", null, "1", "KRW"), NOW).contains("데이터 시각 없음"));
		String unparsable = PriceQueryRunner.describe(new StockPrice("X", "어제 오후", "1", "KRW"), NOW);
		assertTrue(unparsable.contains("해석 불가") && unparsable.contains("어제 오후"), unparsable);
	}

	@Test
	void runWithoutSymbolsDoesNotCallTheApi() {
		PriceService service = mock(PriceService.class);

		assertDoesNotThrow(() -> new PriceQueryRunner(service, CLOCK).run("--spring.profiles.active=prices"));

		verify(service, never()).getPrices(anyList());
	}

	@Test
	void runDropsSpringOptionsAndNormalizesSymbols() {
		PriceService service = mock(PriceService.class);
		when(service.getPrices(List.of("SPCX", "IREN", "005930"))).thenReturn(List.of());

		new PriceQueryRunner(service, CLOCK).run("--spring.profiles.active=prices", "spcx", "IREN,iren", " 005930 ", "");

		// 소문자는 대문자로, 콤마로 묶은 것은 풀어서, 중복은 한 번만, 빈 값은 버린다
		verify(service).getPrices(List.of("SPCX", "IREN", "005930"));
	}

	@Test
	void runDoesNotThrowWhenTheServiceFailsSoTheProcessEndsCleanly() {
		PriceService service = mock(PriceService.class);
		when(service.getPrices(anyList())).thenThrow(new IllegalStateException("현재가 조회 실패: HTTP 403"));

		assertDoesNotThrow(() -> new PriceQueryRunner(service, CLOCK).run("SPCX"));
	}
}
