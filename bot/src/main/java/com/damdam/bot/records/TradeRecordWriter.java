package com.damdam.bot.records;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 계좌 식별값 없이, 실제 체결된 거래 한 건당 한 줄을 CSV에 남긴다.
// 같은 체결이 웹소켓과 재동기화 양쪽에서 들어올 수 있어서 (주문ID, 누적 체결수량)이 같으면 두 번 기록하지 않는다.
@Component
public class TradeRecordWriter {

	public static final String SOURCE_STREAM = "stream";
	public static final String SOURCE_RESYNC = "resync";

	private static final Logger log = LoggerFactory.getLogger(TradeRecordWriter.class);
	private static final String LEGACY_HEADER = "timestamp,order_id,symbol,side,event,filled_quantity,"
		+ "average_filled_price,filled_amount,commission,tax,currency,order_type,status";
	// filled_at: 실제 체결 시각(재동기화 행에서 채움. timestamp는 기록한 시각이라 늦게 기록되면 체결 시각과 다르다), source: stream 또는 resync
	private static final String HEADER = LEGACY_HEADER + ",filled_at,source\n";
	private static final int ORDER_ID_COLUMN = 1;
	private static final int FILLED_QUANTITY_COLUMN = 5;

	private final Path filePath;
	private Set<String> recordedKeys;

	public TradeRecordWriter(@Value("${damdam.records.manual-trades-path}") String path) {
		this.filePath = Path.of(path);
	}

	// 웹소켓 체결 이벤트용 (체결 시각 정보가 이벤트에 없다)
	public boolean record(String orderId, String symbol, String side, String event,
			String filledQuantity, String averageFilledPrice, String filledAmount,
			String commission, String tax, String currency, String orderType, String status) {
		return record(orderId, symbol, side, event, filledQuantity, averageFilledPrice, filledAmount,
			commission, tax, currency, orderType, status, null, SOURCE_STREAM);
	}

	// 새로 기록했으면 true, 이미 기록돼 있거나 저장에 실패했으면 false
	public synchronized boolean record(String orderId, String symbol, String side, String event,
			String filledQuantity, String averageFilledPrice, String filledAmount,
			String commission, String tax, String currency, String orderType, String status,
			String filledAt, String source) {
		try {
			prepareFile();
			String key = key(orderId, filledQuantity);
			if (recordedKeys.contains(key)) {
				return false;
			}
			String row = String.join(",",
				Instant.now().toString(), csv(orderId), csv(symbol), csv(side), csv(event),
				csv(filledQuantity), csv(averageFilledPrice), csv(filledAmount),
				csv(commission), csv(tax), csv(currency), csv(orderType), csv(status),
				csv(filledAt), csv(source)) + "\n";
			Files.writeString(filePath, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
			recordedKeys.add(key);
			return true;
		} catch (IOException e) {
			log.warn("매매 기록 저장 실패: {}", e.getMessage());
			return false;
		}
	}

	// 처음 한 번: 파일이 없으면 만들고, 옛 형식(컬럼 13개)이면 새 컬럼을 붙여 옮기고, 이미 기록된 체결 키를 읽어 둔다
	private void prepareFile() throws IOException {
		if (recordedKeys != null) {
			return;
		}
		Set<String> keys = new HashSet<>();
		if (!Files.exists(filePath)) {
			Path parent = filePath.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(filePath, HEADER, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
		} else {
			List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
			if (!lines.isEmpty() && lines.get(0).equals(LEGACY_HEADER)) {
				migrateLegacyFile(lines);
			}
			for (int i = 1; i < lines.size(); i++) {
				String[] columns = lines.get(i).split(",", -1);
				if (columns.length > FILLED_QUANTITY_COLUMN) {
					keys.add(key(columns[ORDER_ID_COLUMN], columns[FILLED_QUANTITY_COLUMN]));
				}
			}
		}
		recordedKeys = keys;
	}

	// 컬럼이 다른 행이 섞이면 pandas가 읽지 못하므로, 기존 행 끝에 빈 컬럼 두 개를 붙여 통째로 새 형식으로 바꾼다.
	// 임시 파일에 쓴 뒤 교체해서 중간에 종료돼도 원본이 남는다
	private void migrateLegacyFile(List<String> lines) throws IOException {
		StringBuilder migrated = new StringBuilder(HEADER);
		for (int i = 1; i < lines.size(); i++) {
			if (!lines.get(i).isBlank()) {
				migrated.append(lines.get(i)).append(",,\n");
			}
		}
		Path temp = filePath.toAbsolutePath().resolveSibling(filePath.getFileName() + ".tmp");
		Files.writeString(temp, migrated.toString(), StandardCharsets.UTF_8);
		Files.move(temp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		log.info("매매 기록 파일에 filled_at, source 컬럼을 추가했습니다 (기존 {}건 유지).", lines.size() - 1);
	}

	// 웹소켓과 REST의 수량 표기가 다를 수 있어("10"과 "10.000") 숫자로 정규화해서 비교한다
	private static String key(String orderId, String filledQuantity) {
		String quantity = filledQuantity;
		try {
			quantity = new BigDecimal(filledQuantity).stripTrailingZeros().toPlainString();
		} catch (RuntimeException ignored) {
			// 숫자가 아니면 원문 그대로 비교한다
		}
		return orderId + "|" + quantity;
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
