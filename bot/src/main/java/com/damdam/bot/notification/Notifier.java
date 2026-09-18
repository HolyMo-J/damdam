package com.damdam.bot.notification;

// 사람이 바로 알아야 하는 사건(안전장치 발동, OCO 등록 실패, 연결 문제 등)을 외부 채널로 알린다
public interface Notifier {

	// 비동기로 보낸다. 같은 eventKey는 일정 시간 안에 한 번만 나간다 (같은 문제가 반복돼도 알림이 폭주하지 않게)
	void send(String eventKey, String message);

	// 봇 종료 직전처럼 기다릴 수 없는 상황에서 스로틀 없이 즉시(동기) 보낸다
	default void sendNow(String message) {
		send("immediate-" + System.nanoTime(), message);
	}
}
