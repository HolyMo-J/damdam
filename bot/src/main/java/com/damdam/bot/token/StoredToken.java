package com.damdam.bot.token;

import java.time.Instant;

record StoredToken(String accessToken, String tokenType, Instant issuedAt, long expiresInSeconds) {

	// 만료 시각 전 60초를 여유로 두어, 호출 도중 만료되는 상황을 피한다
	private static final long EXPIRY_BUFFER_SECONDS = 60;

	boolean isExpired() {
		return Instant.now().isAfter(issuedAt.plusSeconds(expiresInSeconds - EXPIRY_BUFFER_SECONDS));
	}
}
