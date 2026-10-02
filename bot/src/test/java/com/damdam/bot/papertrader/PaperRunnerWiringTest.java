package com.damdam.bot.papertrader;

import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.notification.Notifier;
import com.damdam.bot.papertrading.SignalScanService;
import com.damdam.bot.papertrading.TargetUniverseService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

// 러너는 paper-run 프로필이 켜져야만 빈으로 만들어져서, 일반 테스트(기본 프로필)로는 생성자 조립 오류가 드러나지 않는다.
// 생성자가 둘이라 @Autowired가 없거나 설정 키 이름이 틀리면 프로필을 켜고 실행하는 순간에야 실패하므로 여기서 미리 확인한다.
// 특히 "다른 프로필에서는 만들어지지 않는다"는 1단계 청산 봇(listen 프로필)이 떠 있을 때 가상매매 러너가 같이 돌지 않는다는 뜻이다
class PaperRunnerWiringTest {

	private AnnotationConfigApplicationContext contextWith(String... profiles) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profiles);
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
			"damdam.paper.state-dir", "data/paper",
			"damdam.paper.records-dir", "../records/paper",
			"damdam.paper.lock-file", "data/paper/paper-run.lock",
			"damdam.paper.slippage", "0.002",
			"damdam.paper.strategy-version", "v1",
			"damdam.paper.reference-symbol", "000001")));
		context.registerBean(TargetUniverseService.class, () -> mock(TargetUniverseService.class));
		context.registerBean(SignalScanService.class, () -> mock(SignalScanService.class));
		context.registerBean(SettlementBarsLoader.class, () -> mock(SettlementBarsLoader.class));
		context.registerBean(MarketCalendarService.class, () -> mock(MarketCalendarService.class));
		context.registerBean(Notifier.class, () -> mock(Notifier.class));
		context.register(PaperRunner.class);
		context.refresh();
		return context;
	}

	@Test
	void runnerIsCreatedWhenThePaperRunProfileIsActive() {
		try (AnnotationConfigApplicationContext context = contextWith("paper-run")) {
			assertEquals(1, context.getBeansOfType(PaperRunner.class).size());
		}
	}

	@Test
	void runnerIsNotCreatedWithoutTheProfileOrUnderOtherProfiles() {
		try (AnnotationConfigApplicationContext context = contextWith()) {
			assertEquals(0, context.getBeansOfType(PaperRunner.class).size());
		}
		try (AnnotationConfigApplicationContext context = contextWith("listen")) {
			assertEquals(0, context.getBeansOfType(PaperRunner.class).size());
		}
	}
}
