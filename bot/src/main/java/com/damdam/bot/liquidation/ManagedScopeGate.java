package com.damdam.bot.liquidation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

// 이 기능을 처음 실행한 시각을 기록해두고, 그 이후에 새로 산 종목만 자동 매도 대상으로 삼는다
@Component
class ManagedScopeGate {

	private static final Logger log = LoggerFactory.getLogger(ManagedScopeGate.class);

	private final Path markerFilePath;
	private OffsetDateTime scopeStart;

	ManagedScopeGate(@Value("${damdam.orders.scope-start-path}") String markerFilePath) {
		this.markerFilePath = Path.of(markerFilePath);
	}

	synchronized OffsetDateTime scopeStart() {
		if (scopeStart != null) {
			return scopeStart;
		}
		scopeStart = readExisting();
		if (scopeStart == null) {
			scopeStart = OffsetDateTime.now();
			writeMarker(scopeStart);
		}
		return scopeStart;
	}

	private OffsetDateTime readExisting() {
		if (!Files.exists(markerFilePath)) {
			return null;
		}
		try {
			return OffsetDateTime.parse(Files.readString(markerFilePath).trim());
		} catch (IOException | DateTimeParseException e) {
			log.warn("자동 매도 관리 시작 시각 파일을 읽지 못해 새로 기록합니다.");
			return null;
		}
	}

	private void writeMarker(OffsetDateTime value) {
		try {
			Path parent = markerFilePath.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(markerFilePath, value.toString());
		} catch (IOException e) {
			log.warn("자동 매도 관리 시작 시각 저장 실패: {}", e.getMessage());
		}
	}
}
