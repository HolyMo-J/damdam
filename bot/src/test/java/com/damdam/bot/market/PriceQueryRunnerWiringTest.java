package com.damdam.bot.market;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

// 러너는 prices 프로필이 켜져야만 빈으로 만들어져서 일반 테스트(기본 프로필)로는 생성자 조립 오류가 드러나지 않는다.
// 생성자가 둘이라 공개 생성자에 @Autowired가 없으면 프로필을 켜고 실행하는 순간에야 실패하므로 여기서 미리 확인한다
class PriceQueryRunnerWiringTest {

	private AnnotationConfigApplicationContext contextWith(String... profiles) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profiles);
		context.registerBean(PriceService.class, () -> mock(PriceService.class));
		context.register(PriceQueryRunner.class);
		context.refresh();
		return context;
	}

	@Test
	void runnerIsCreatedWhenThePricesProfileIsActive() {
		try (AnnotationConfigApplicationContext context = contextWith("prices")) {
			assertEquals(1, context.getBeansOfType(PriceQueryRunner.class).size());
		}
	}

	@Test
	void runnerIsNotCreatedWithoutTheProfileSoNormalStartupNeverQueriesPrices() {
		try (AnnotationConfigApplicationContext context = contextWith()) {
			assertEquals(0, context.getBeansOfType(PriceQueryRunner.class).size());
		}
		try (AnnotationConfigApplicationContext context = contextWith("listen")) {
			assertEquals(0, context.getBeansOfType(PriceQueryRunner.class).size());
		}
	}
}
