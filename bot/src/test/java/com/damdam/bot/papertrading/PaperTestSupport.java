package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

// 가상매매 순수 로직 테스트가 함께 쓰는 입력 조립 도구
final class PaperTestSupport {

	static final String STRATEGY = "B";
	static final LocalDate SIGNAL_DATE = LocalDate.of(2026, 9, 28);   // 월요일
	static final LocalDate ENTRY_DATE = LocalDate.of(2026, 9, 29);    // 화요일

	private PaperTestSupport() {
	}

	static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	static Candle bar(LocalDate date, String open, String high, String low, String close, String volume) {
		String timestamp = date.atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toString();
		return new Candle(timestamp, open, high, low, close, volume, "KRW");
	}

	// 거래량 1000의 일반 봉
	static Candle bar(LocalDate date, String open, String high, String low, String close) {
		return bar(date, open, high, low, close, "1000");
	}

	static PendingSignal signal(String symbol, int rank, String atr, String signalClose) {
		return new PendingSignal(STRATEGY, symbol, rank, SIGNAL_DATE, bd(atr), bd(signalClose));
	}

	// 슬리피지 0으로 진입시킨 포지션. 진입가는 open이고 익절가, 손절 감시가, 손절 주문가는 AtrOcoPricing 그대로다
	// (예: open 10000, atr 100 -> 익절 10100, 손절 감시 9900, 손절 주문 9899)
	static PaperPosition position(String symbol, String open, String atr) {
		Candle entryBar = bar(ENTRY_DATE, open, open, open, open);
		PaperEntry.Result result = PaperEntry.enter(signal(symbol, 1, atr, open), ENTRY_DATE, entryBar, BigDecimal.ZERO);
		return ((PaperEntry.Entered) result).position();
	}

	// barsProcessed를 n으로 만든다 (진입 봉 이후 n번째 봉을 처리할 차례). 마지막 처리일은 진입일로 둔다
	// (이후 테스트가 쓰는 봉 날짜는 진입일 다음 날부터라 "이미 처리한 봉" 검사에 걸리지 않는다)
	static PaperPosition atBar(PaperPosition position, int barsProcessed) {
		PaperPosition p = position;
		while (p.barsProcessed() < barsProcessed) {
			p = p.advanced(ENTRY_DATE, p.sellFailed());
		}
		return p;
	}
}
