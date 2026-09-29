package com.damdam.bot.orders;

public record OrderPlacementResult(Status status, String orderId, String clientOrderId, String errorMessage) {

	public enum Status {
		// live-mode가 꺼져 있어 실제로 전송하지 않음
		SIMULATED,
		// 실제로 전송해서 접수됨
		PLACED,
		// 전송을 시도했으나 거부되거나 실패함
		FAILED,
		// 전송했지만 응답을 받지 못해(타임아웃, 연결 끊김) 접수됐는지 알 수 없음. 실패로 단정하면 이미 접수된 주문을 놓친다
		UNKNOWN
	}
}
