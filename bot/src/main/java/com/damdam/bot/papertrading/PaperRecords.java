package com.damdam.bot.papertrading;

import com.damdam.bot.market.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// 가상매매 CSV 파일들의 열과 행 변환 (docs/records.md "기록 항목"). 앞쪽 열이 중복 판정 키다.
// 값은 영문자, 숫자, 기호만 쓴다 (엑셀에서 열어도 인코딩 문제가 없게)
final class PaperRecords {

	static final String EXECUTION_MODE = "daily_batch";
	static final String ENTRY_PRICE_SOURCE = "open_plus_slippage";

	// 키: 전략, 종목, 신호일 (같은 신호에서 거래가 둘 나올 수 없다)
	static final List<String> TRADES_HEADER = List.of("strategy", "symbol", "signal_date", "entry_date", "exit_date", "rank",
		"quantity", "entry_price", "exit_price", "exit_reason", "net_profit", "net_return", "gap_exit", "exit_on_entry_day",
		"entry_clamped_to_high", "sell_failed", "entry_gap_rate", "execution_mode", "entry_price_source", "slippage_rate",
		"strategy_version");
	static final int TRADES_KEY = 3;

	// 키: 전략, 종목, 신호일
	static final List<String> SKIPS_HEADER = List.of("strategy", "symbol", "signal_date", "reason", "rank", "settle_date", "atr",
		"signal_close", "execution_mode", "slippage_rate", "strategy_version");
	static final int SKIPS_KEY = 3;

	// 키: 날짜, 종목. 정산에 쓴 일봉 원본이라 같은 코드로 슬리피지만 바꿔 다시 돌릴 때 입력이 된다
	static final List<String> BARS_HEADER = List.of("date", "symbol", "open", "high", "low", "close", "volume");
	static final int BARS_KEY = 2;

	// 키: 실행 시각, 전략, 정산일, 상태. 상태가 키에 있어서 같은 실행의 정산 행(SETTLED)과 공백 행(SIGNAL_GAP)이 시계 해상도와 무관하게 겹치지 않는다. 거래일 처리마다 한 줄이라 "신호 없음"과 "돌지 못한 공백"을 구분하는 근거가 된다
	static final List<String> RUNS_HEADER = List.of("run_at", "strategy", "settle_date", "status", "new_trades",
		"skipped_signals", "positions_after", "unsettled_symbols", "abandoned_symbols", "note");
	static final int RUNS_KEY = 4;

	// 키: 전략, 종목, 신호일. 신호일 저녁에 만든 대기 신호. 청산되면 상태에서 사라지는 순위와 신호일 ATR과 종가를 남겨
	// 슬리피지만 바꿔 같은 코드로 다시 돌릴 때의 입력이 된다
	static final List<String> SIGNALS_HEADER = List.of("strategy", "symbol", "signal_date", "rank", "atr", "signal_close");
	static final int SIGNALS_KEY = 3;

	// 키: 전략, 종목, 신호일. 봉이 끝내 오지 않아 포기한 보유 포지션 (거래가 아니라 "청산되지 않은 포지션"이다)
	static final List<String> ABANDONED_HEADER = List.of("strategy", "symbol", "signal_date", "entry_date", "abandoned_on",
		"rank", "quantity", "entry_price", "take_profit_price", "stop_trigger", "atr", "bars_processed", "last_bar_date",
		"execution_mode", "slippage_rate", "strategy_version");
	static final int ABANDONED_KEY = 3;

	private PaperRecords() {
	}

	static List<String> signalRow(PendingSignal s) {
		return List.of(s.strategy(), s.symbol(), s.signalDate().toString(), String.valueOf(s.rank()), plain(s.atr()),
			plain(s.signalClose()));
	}

	static List<String> abandonedRow(PaperPosition p, LocalDate abandonedOn, BigDecimal slippage, String strategyVersion) {
		return List.of(p.strategy(), p.symbol(), p.signalDate().toString(), p.entryDate().toString(), abandonedOn.toString(),
			String.valueOf(p.rank()), String.valueOf(p.quantity()), plain(p.entryPrice()), plain(p.takeProfitPrice()),
			plain(p.stopTrigger()), plain(p.atr()), String.valueOf(p.barsProcessed()),
			p.lastBarDate() == null ? "" : p.lastBarDate().toString(), EXECUTION_MODE, plain(slippage), strategyVersion);
	}

	static List<String> tradeRow(PaperTrade t, BigDecimal slippage, String strategyVersion) {
		return List.of(t.strategy(), t.symbol(), t.signalDate().toString(), t.entryDate().toString(), t.exitDate().toString(),
			String.valueOf(t.rank()), String.valueOf(t.quantity()), plain(t.entryPrice()), plain(t.exitPrice()),
			t.exitReason().name(), plain(t.netProfit()), plain(t.netReturn()), String.valueOf(t.gapExit()),
			String.valueOf(t.exitOnEntryDay()), String.valueOf(t.entryClampedToHigh()), String.valueOf(t.sellFailed()),
			plain(t.entryGapRate()), EXECUTION_MODE, ENTRY_PRICE_SOURCE, plain(slippage), strategyVersion);
	}

	static List<String> skipRow(PaperDaySettlement.SkippedSignal skipped, LocalDate settleDate, BigDecimal slippage,
			String strategyVersion) {
		PendingSignal s = skipped.signal();
		return List.of(s.strategy(), s.symbol(), s.signalDate().toString(), skipped.reason().name(), String.valueOf(s.rank()),
			settleDate.toString(), plain(s.atr()), plain(s.signalClose()), EXECUTION_MODE, plain(slippage), strategyVersion);
	}

	static List<String> barRow(String symbol, Candle bar) {
		return List.of(DailyCandles.dateOf(bar).toString(), symbol, bar.openPrice(), bar.highPrice(), bar.lowPrice(),
			bar.closePrice(), bar.volume());
	}

	static List<String> runRow(Instant runAt, String strategy, String status, LocalDate settleDate, int newTrades,
			int skippedSignals, int positionsAfter, List<String> unsettled, List<String> abandoned, String note) {
		return List.of(runAt.toString(), strategy, settleDate == null ? "" : settleDate.toString(), status,
			String.valueOf(newTrades), String.valueOf(skippedSignals), String.valueOf(positionsAfter),
			String.join(";", unsettled), String.join(";", abandoned), note);
	}

	private static String plain(BigDecimal value) {
		return value.toPlainString();
	}
}
