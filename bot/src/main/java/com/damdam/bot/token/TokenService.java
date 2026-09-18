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
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

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
			// 내용을 쓰기 전에 빈 파일을 만들어 권한부터 제한한다. 그렇지 않으면 토큰이 적힌 파일이 잠깐 기본 권한으로 존재한다
			if (!Files.exists(tokenFilePath)) {
				Files.createFile(tokenFilePath);
			}
			restrictToOwnerOnly(tokenFilePath);
			objectMapper.writeValue(tokenFilePath.toFile(), token);
		} catch (IOException | JacksonException e) {
			throw new IllegalStateException("토큰 파일 저장에 실패했습니다.", e);
		}
	}

	// java.io.File의 setReadable(false)는 Windows에서 아무 효과가 없어서(false를 반환하고 그대로 읽힘) 쓰지 않는다.
	// 파일 시스템에 맞는 표준 API로 소유자만 접근하게 만든다: POSIX는 rw-------, Windows(NTFS)는 소유자만 허용하는 ACL(상속 항목 제거)
	private void restrictToOwnerOnly(Path path) {
		try {
			PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
			if (posix != null) {
				posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
				return;
			}
			AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
			if (acl != null) {
				UserPrincipal owner = Files.getOwner(path);
				acl.setAcl(List.of(AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(owner)
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build()));
				return;
			}
			log.warn("이 파일 시스템에서는 토큰 파일 접근 권한을 제한할 수 없습니다. 토큰 파일이 다른 사용자에게 읽힐 수 있는지 직접 확인하세요.");
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			// 조용히 넘기지 않는다: 권한이 제한됐다고 착각하면 안 된다
			log.warn("토큰 파일 접근 권한을 제한하지 못했습니다: {}", e.getMessage());
		}
	}
}
