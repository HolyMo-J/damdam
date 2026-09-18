package com.damdam.bot.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// listen 프로필에서 몇 초마다 제어 파일을 확인한다.
// 1) 정지 파일: 결정 시점 전에도 상태 변화를 바로 알리려고 주기적으로 확인한다
// 2) 종료 요청 파일: Windows에서 Stop-Process는 JVM 종료 훅을 실행하지 못해 "봇 종료" 알림이 안 나간다.
//    stop-listen.ps1이 이 파일을 만들면 봇이 스스로 정상 종료(종료 훅 실행)한다
@Component
@Profile("listen")
class ControlFileWatcher {

	private static final Logger log = LoggerFactory.getLogger(ControlFileWatcher.class);

	private final TradingHaltSwitch haltSwitch;
	private final Path shutdownRequestFile;
	private boolean shuttingDown;

	ControlFileWatcher(TradingHaltSwitch haltSwitch,
			@Value("${damdam.control.shutdown-request-path}") String shutdownRequestPath) {
		this.haltSwitch = haltSwitch;
		this.shutdownRequestFile = Path.of(shutdownRequestPath);
	}

	@Scheduled(fixedDelay = 5000)
	void poll() {
		haltSwitch.isHalted();
		if (!shuttingDown && Files.exists(shutdownRequestFile)) {
			shuttingDown = true;
			try {
				Files.deleteIfExists(shutdownRequestFile);
			} catch (IOException e) {
				log.warn("종료 요청 파일 삭제 실패: {}", e.getMessage());
			}
			log.warn("[종료 요청] 종료 요청 파일이 감지돼 봇을 정상 종료합니다.");
			// 스케줄러 스레드에서 직접 System.exit를 부르면 종료 훅과 스케줄러 정리가 서로 기다릴 수 있어 별도 스레드로 호출한다
			Thread exitThread = new Thread(() -> System.exit(0), "shutdown-request");
			exitThread.start();
		}
	}
}
