package com.damdam.bot.papertrader;

import com.damdam.bot.market.Candle;
import com.damdam.bot.papertrading.IchimokuCloudBreakout;
import com.damdam.bot.papertrading.InstitutionNetBuySignal;
import com.damdam.bot.papertrading.SignalScan;
import com.damdam.bot.papertrading.StrategyOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

final class PaperTraderTestSupport {

	static final ZoneId KST = ZoneId.of("Asia/Seoul");
	static final LocalDate SIGNAL_DATE = LocalDate.of(2026, 10, 2);   // 금요일
	static final String REFERENCE = "000001";

	private PaperTraderTestSupport() {
	}

	static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	// 한국 시각 "2026-10-02T20:17:34"을 시점으로 바꾼다
	static Instant kst(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(KST).toInstant();
	}

	static ZonedDateTime kstTime(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(KST);
	}

	static Candle candle(LocalDate date, String open, String high, String low, String close, String volume) {
		String timestamp = date.atStartOfDay().atOffset(ZoneOffset.ofHours(9)).toString();
		return new Candle(timestamp, open, high, low, close, volume, "KRW");
	}

	// 판정을 끝낸 행. 신호 여부만 정하고 근거 값은 임의의 일관된 값이다
	static SignalScan.Row row(int rank, String symbol, boolean ichimokuSignal, boolean institutionSignal, Instant flowUpdatedAt,
			SignalScan.EntryBasis basis) {
		IchimokuCloudBreakout.Result b = new IchimokuCloudBreakout.Result(SIGNAL_DATE, ichimokuSignal, ichimokuSignal,
			bd("10000"), bd("9900"), bd("9800"), bd("9850"), bd("2000"), bd("1000"));
		InstitutionNetBuySignal.Result a = new InstitutionNetBuySignal.Result(SIGNAL_DATE, institutionSignal, institutionSignal,
			bd("300"), bd("3000"), bd("10"));
		return new SignalScan.Row(rank, symbol, "이름" + symbol,
			new StrategyOutcome<>(StrategyOutcome.Status.EVALUATED, b, null),
			new StrategyOutcome<>(StrategyOutcome.Status.EVALUATED, a, null), flowUpdatedAt, basis);
	}

	static SignalScan.EntryBasis basis(String atr, String close) {
		return new SignalScan.EntryBasis(bd(atr), bd(close));
	}

	static SignalScan scan(Instant scannedAt, SignalScan.Row... rows) {
		return scanOn(SIGNAL_DATE, scannedAt, rows);
	}

	static SignalScan scanOn(LocalDate signalDate, Instant scannedAt, SignalScan.Row... rows) {
		return new SignalScan(signalDate, scannedAt, List.of(rows));
	}
}
