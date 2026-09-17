package com.damdam.bot.records;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

// 계좌 식별값 없이, 실제 체결된 거래 한 건당 한 줄을 CSV에 남긴다
@Component
public class TradeRecordWriter {

	private static final Logger log = LoggerFactory.getLogger(TradeRecordWriter.class);
	private static final String HEADER = "timestamp,order_id,symbol,side,event,filled_quantity,"
		+ "average_filled_price,filled_amount,commission,tax,currency,order_type,status\n";

	private final Path filePath;

	public TradeRecordWriter(@Value("${damdam.records.manual-trades-path}") String path) {
		this.filePath = Path.of(path);
	}

	public synchronized void record(String orderId, String symbol, String side, String event,
			String filledQuantity, String averageFilledPrice, String filledAmount,
			String commission, String tax, String currency, String orderType, String status) {
		String row = String.join(",",
			Instant.now().toString(), csv(orderId), csv(symbol), csv(side), csv(event),
			csv(filledQuantity), csv(averageFilledPrice), csv(filledAmount),
			csv(commission), csv(tax), csv(currency), csv(orderType), csv(status)) + "\n";

		try {
			ensureFileWithHeader();
			Files.writeString(filePath, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("매매 기록 저장 실패: {}", e.getMessage());
		}
	}

	private void ensureFileWithHeader() throws IOException {
		if (Files.exists(filePath)) {
			return;
		}
		Path parent = filePath.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(filePath, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
	}

	private String csv(String value) {
		if (value == null) {
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
			return "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}
}
