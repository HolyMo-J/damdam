package com.damdam.bot.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// prices 프로필: 종목의 현재가를 한 번 조회해 보여주고 끝난다 (조회 전용, 주문 코드 없음).
// 사용법: ./gradlew bootRun --args='--spring.profiles.active=prices 005930 AAPL SPCX IREN'
// 각 종목의 데이터 시각과 조회 시각의 차이를 함께 보여준다. 차이가 크면 지연 시세이거나 장이 닫혀 마지막 체결 시각이 남은 것일 수 있다
// (해외 시세가 실시간인지는 명세에 없어서 확인 못 함이고, 이 차이를 장중에 여러 번 재서 판단한다)
@Component
@Profile("prices")
public class PriceQueryRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(PriceQueryRunner.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final PriceService priceService;
	private final Clock clock;

	@Autowired
	public PriceQueryRunner(PriceService priceService) {
		this(priceService, Clock.system(KST));
	}

	PriceQueryRunner(PriceService priceService, Clock clock) {
		this.priceService = priceService;
		this.clock = clock;
	}

	@Override
	public void run(String... args) {
		// 스프링 옵션(--로 시작)은 빼고 남은 것을 심볼로 본다. 콤마로 묶어 줘도 풀어서 쓴다
		List<String> symbols = Arrays.stream(args)
			.filter(arg -> !arg.startsWith("--"))
			.flatMap(arg -> Arrays.stream(arg.split(",")))
			.map(String::trim)
			.filter(arg -> !arg.isEmpty())
			.map(arg -> arg.toUpperCase(Locale.ROOT))
			.distinct()
			.toList();
		if (symbols.isEmpty()) {
			log.warn("[현재가] 조회할 심볼이 없습니다. 예: --args='--spring.profiles.active=prices 005930 AAPL'");
			return;
		}

		Instant now = clock.instant();
		List<StockPrice> prices;
		try {
			prices = priceService.getPrices(symbols);
		} catch (RuntimeException e) {
			// 서비스가 상태 코드만 담은 메시지로 감싸서 던진다 (토큰과 응답 본문은 로그에 남기지 않는다)
			log.error("[현재가] {}", e.getMessage());
			return;
		}

		log.info("[현재가] 조회 시각 {} (KST), 요청 {}개, 응답 {}개", TIME.format(ZonedDateTime.ofInstant(now, KST)), symbols.size(), prices.size());
		Set<String> answered = new HashSet<>();
		for (StockPrice price : prices) {
			answered.add(price.symbol() == null ? "" : price.symbol().toUpperCase(Locale.ROOT));
			log.info("[현재가]   {}", describe(price, now));
		}
		for (String symbol : symbols) {
			if (!answered.contains(symbol)) {
				log.warn("[현재가]   {}: 응답에 없음 (심볼이 틀렸거나 조회되지 않은 종목)", symbol);
			}
		}
		log.info("[현재가] 시각 차이가 큰 종목은 지연 시세이거나 장이 닫혀 마지막 체결 시각이 남은 것일 수 있습니다 (해외 시세 지연 여부는 확인 못 함)");
	}

	// 한 줄 설명. 데이터 시각이 없으면(체결 미발생) 그렇게 쓰고, 해석할 수 없는 시각은 원문을 그대로 보여준다
	static String describe(StockPrice price, Instant now) {
		String head = price.symbol() + " " + price.lastPrice() + " " + price.currency();
		String timestamp = price.timestamp();
		if (timestamp == null) {
			return head + " (데이터 시각 없음, 체결 미발생)";
		}
		try {
			OffsetDateTime dataTime = OffsetDateTime.parse(timestamp);
			double seconds = Duration.between(dataTime.toInstant(), now).toMillis() / 1000.0;
			return head + String.format(Locale.ROOT, " (데이터 시각 %s, 조회 시각과 차이 %.1f초)", timestamp, seconds);
		} catch (DateTimeParseException e) {
			return head + " (데이터 시각 해석 불가: " + timestamp + ")";
		}
	}
}
