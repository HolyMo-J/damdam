package com.damdam.bot.token;

import com.damdam.bot.config.TossApiProperties;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

// 새 토큰 발급 시 이전 토큰이 무효화되므로, 클라이언트당 하나의 토큰만 파일에 저장해 재사용한다
@Service
public class TokenService {

	private static final Logger log = LoggerFactory.getLogger(TokenService.class);

	private final RestClient restClient;
	private final TossApiProperties properties;
	private final ObjectMapper objectMapper;
	private final Path tokenFilePath;

	public TokenService(RestClient tossRestClient, TossApiProperties properties, ObjectMapper objectMapper) {
		this.restClient = tossRestClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.tokenFilePath = Path.of(properties.tokenFilePath());
	}

	public synchronized String getAccessToken() {
		StoredToken stored = readStoredToken();
		if (stored != null && !stored.isExpired()) {
			return stored.accessToken();
		}
		return issueNewToken();
	}

	private String issueNewToken() {
		log.info("새 액세스 토큰을 발급받습니다.");

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", properties.key());
		form.add("client_secret", properties.secret());

		TokenResponse response = restClient.post()
			.uri("/oauth2/token")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(TokenResponse.class);

		if (response == null || response.accessToken() == null) {
			throw new IllegalStateException("토큰 응답이 비어 있습니다.");
		}

		StoredToken toStore = new StoredToken(
			response.accessToken(),
			response.tokenType(),
			Instant.now(),
			response.expiresInSeconds()
		);
		writeStoredToken(toStore);
		log.info("토큰 발급 완료. 유효시간 {}초", response.expiresInSeconds());
		return toStore.accessToken();
	}

	private StoredToken readStoredToken() {
		if (!Files.exists(tokenFilePath)) {
			return null;
		}
		try {
			return objectMapper.readValue(tokenFilePath.toFile(), StoredToken.class);
		} catch (JacksonException e) {
			log.warn("저장된 토큰 파일을 읽지 못해 새로 발급합니다.");
			return null;
		}
	}

	private void writeStoredToken(StoredToken token) {
		try {
			Path parentDir = tokenFilePath.toAbsolutePath().getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
			objectMapper.writeValue(tokenFilePath.toFile(), token);
			restrictToOwnerOnly(tokenFilePath);
		} catch (IOException | JacksonException e) {
			throw new IllegalStateException("토큰 파일 저장에 실패했습니다.", e);
		}
	}

	private void restrictToOwnerOnly(Path path) {
		var file = path.toFile();
		file.setReadable(false, false);
		file.setWritable(false, false);
		file.setReadable(true, true);
		file.setWritable(true, true);
	}
}
