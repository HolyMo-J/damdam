package com.damdam.bot.orders;

public record OrderPlacementResult(Status status, String orderId, String clientOrderId, String errorMessage) {

	public enum Status {
		// live-mode가 꺼져 있어 실제로 전송하지 않음
		SIMULATED,
		// 실제로 전송해서 접수됨
		PLACED,
		// 전송을 시도했으나 거부되거나 실패함
		FAILED
	}
}
