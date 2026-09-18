package com.damdam.bot.control;

import com.damdam.bot.notification.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

// 정지 파일(기본 bot/data/STOP)이 있으면 자동 매도와 OCO 등록/수정을 멈춘다. 재시작 없이 파일만 만들거나 지우면 된다.
// 이미 토스 서버에 등록된 OCO는 이 스위치와 무관하게 계속 동작하고, OCO 취소는 막지 않는다 (보호 장치를 걷어내는 쪽은 막을 이유가 없다).
@Component
public class TradingHaltSwitch {

	private static final Logger log = LoggerFactory.getLogger(TradingHaltSwitch.class);

	private final Path haltFile;
	private final Notifier notifier;
	private boolean lastHalted;

	public TradingHaltSwitch(@Value("${damdam.control.halt-file-path}") String haltFilePath, Notifier notifier) {
		this.haltFile = Path.of(haltFilePath);
		this.notifier = notifier;
	}

	// 결정 시점마다 파일을 확인한다. 상태가 바뀔 때(정지 시작, 정지 해제)만 한 번씩 알린다
	public synchronized boolean isHalted() {
		// notExists는 "확실히 없을 때"만 true다. 접근 오류처럼 판단할 수 없으면 정지로 취급한다
		boolean halted = !Files.notExists(haltFile);
		if (halted && !lastHalted) {
			log.warn("[정지 파일] {} 파일이 감지돼 자동 매도와 OCO 등록을 멈춥니다.", haltFile.getFileName());
			notifier.send("halt-on", "[담담] 정지 파일이 감지돼 자동 매도와 OCO 등록/수정을 멈췄습니다. "
				+ "이미 등록된 OCO는 토스 서버에서 계속 동작합니다. 재개하려면 정지 파일을 삭제하세요.");
		} else if (!halted && lastHalted) {
			log.warn("[정지 파일] 파일이 사라져 자동 매도와 OCO 등록을 재개합니다.");
			notifier.send("halt-off", "[담담] 정지 파일이 사라져 자동 매도와 OCO 등록을 재개합니다.");
		}
		lastHalted = halted;
		return halted;
	}
}
