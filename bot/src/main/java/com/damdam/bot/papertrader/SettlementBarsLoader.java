package com.damdam.bot.papertrader;

import com.damdam.bot.market.Candle;
import com.damdam.bot.market.MarketCalendarService;
import com.damdam.bot.papertrading.DailyCandles;
import com.damdam.bot.stocks.SignalInputService;
import com.damdam.bot.stocks.StockLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

// 원장 정산(PaperLedger.settleDays)에 넘길 "거래일별 종목별 일봉"을 만든다. 조회 전용이다.
// 거래일은 시장 캘린더로 구하고(원장은 캘린더를 모른다), 종목마다 일봉을 한 번만 받아 날짜별로 나눈다.
// 일봉 조회가 종목 단위로 실패하면 그 종목은 어느 날짜에도 들어가지 않고 failedSymbols에 남는다. 원장이 봉이 없는 종목을 미확정으로 다룬다.
// 거래일마다 맵을 반드시 만든다(그날 봉이 하나도 없어도 빈 맵): 원장이 "맵이 통째로 비었다 = 조회 전체의 결측"으로 판단하는 근거이기 때문이다
@Component
class SettlementBarsLoader {

	private static final Logger log = LoggerFactory.getLogger(SettlementBarsLoader.class);

	// 일봉을 100봉(SignalInputService.CANDLE_COUNT)만 받으므로 그보다 많은 거래일을 밀린 채로는 메울 수 없다. 여유를 두고 90일로 막는다.
	// PC가 이만큼 꺼져 있었다면 조용히 일부만 정산하지 않고 실패로 알리고 사람이 판단한다
	static final int MAX_CATCH_UP_TRADING_DAYS = 90;
	// MARKET_DATA_CHART 그룹은 초당 20회라 종목 사이 60ms면 한도 안이다 (보유와 대기 종목 수십 개 규모)
	private static final long PAUSE_BETWEEN_SYMBOLS_MS = 60;

	record Loaded(SortedMap<LocalDate, Map<String, Candle>> barsByDate, List<String> failedSymbols) {
	}

	private final Function<String, List<Candle>> candleSource;
	private final Predicate<LocalDate> isTradingDay;
	private final long pauseMs;

	@Autowired
	SettlementBarsLoader(SignalInputService inputService, MarketCalendarService calendarService) {
		this(inputService::getDailyCandles, calendarService::isTradingDay, PAUSE_BETWEEN_SYMBOLS_MS);
	}

	SettlementBarsLoader(Function<String, List<Candle>> candleSource, Predicate<LocalDate> isTradingDay, long pauseMs) {
		this.candleSource = candleSource;
		this.isTradingDay = isTradingDay;
		this.pauseMs = pauseMs;
	}

	// from부터 signalDate까지(포함)의 거래일이 대상이다. from이 signalDate보다 뒤이면(이미 정산이 끝난 날의 재실행) 날짜가 없는 빈 결과다
	Loaded load(LocalDate from, LocalDate signalDate, Collection<String> symbols) {
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate date = from; !date.isAfter(signalDate); date = date.plusDays(1)) {
			if (isTradingDay.test(date)) {
				days.add(date);
			}
		}
		if (days.size() > MAX_CATCH_UP_TRADING_DAYS) {
			throw new IllegalStateException("밀린 거래일이 %d일이라 일봉 100봉으로 메울 수 없습니다 (%s부터 %s까지, 한도 %d일)."
				.formatted(days.size(), from, signalDate, MAX_CATCH_UP_TRADING_DAYS));
		}

		SortedMap<LocalDate, Map<String, Candle>> barsByDate = new TreeMap<>();
		for (LocalDate day : days) {
			barsByDate.putIfAbsent(day, new HashMap<>());
		}
		List<String> failed = new ArrayList<>();
		if (days.isEmpty()) {
			return new Loaded(barsByDate, List.copyOf(failed));
		}

		boolean first = true;
		for (String symbol : symbols) {
			if (!first) {
				pause();
			}
			first = false;
			List<Candle> candles;
			try {
				candles = candleSource.apply(symbol);
			} catch (StockLookupException e) {
				log.warn("[정산 봉] {} 일봉 조회에 실패했습니다: {}", symbol, e.getMessage());
				failed.add(symbol);
				continue;
			}
			for (Candle candle : candles) {
				LocalDate date;
				try {
					date = DailyCandles.dateOf(candle);
				} catch (DateTimeException e) {
					log.warn("[정산 봉] {} 날짜를 읽지 못한 봉은 건너뜁니다: {}", symbol, e.getMessage());
					continue;
				}
				Map<String, Candle> dayBars = barsByDate.get(date);
				if (dayBars != null) {
					dayBars.putIfAbsent(symbol, candle);
				}
			}
		}
		return new Loaded(barsByDate, List.copyOf(failed));
	}

	private void pause() {
		if (pauseMs <= 0) {
			return;
		}
		try {
			Thread.sleep(pauseMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
