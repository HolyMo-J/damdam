package com.damdam.bot.token;

import com.fasterxml.jackson.annotation.JsonProperty;

record TokenResponse(
	@JsonProperty("access_token") String accessToken,
	@JsonProperty("token_type") String tokenType,
	@JsonProperty("expires_in") long expiresInSeconds
) {
}
