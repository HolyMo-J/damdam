package com.damdam.bot.papertrading;

import com.damdam.bot.market.MarketCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

// 러너는 signal-scan 프로필이 켜져야만 빈으로 만들어져서, 일반 테스트(기본 프로필)로는 생성자 조립 오류가 드러나지 않는다.
// 생성자가 둘이라 공개 생성자에 @Autowired가 없으면 프로필을 켜고 실행하는 순간에야 실패하므로, 여기서 미리 확인한다
class SignalScanRunnerWiringTest {

	private AnnotationConfigApplicationContext contextWith(String... profiles) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profiles);
		context.registerBean(TargetUniverseService.class, () -> mock(TargetUniverseService.class));
		context.registerBean(SignalScanService.class, () -> mock(SignalScanService.class));
		context.registerBean(MarketCalendarService.class, () -> mock(MarketCalendarService.class));
		context.register(SignalScanRunner.class);
		context.refresh();
		return context;
	}

	@Test
	void runnerIsCreatedWhenTheSignalScanProfileIsActive() {
		try (AnnotationConfigApplicationContext context = contextWith("signal-scan")) {
			assertEquals(1, context.getBeansOfType(SignalScanRunner.class).size());
		}
	}

	@Test
	void runnerIsNotCreatedWithoutTheProfileSoNormalStartupNeverRunsIt() {
		try (AnnotationConfigApplicationContext context = contextWith()) {
			assertEquals(0, context.getBeansOfType(SignalScanRunner.class).size());
		}
		try (AnnotationConfigApplicationContext context = contextWith("listen")) {
			assertEquals(0, context.getBeansOfType(SignalScanRunner.class).size());
		}
	}
}
