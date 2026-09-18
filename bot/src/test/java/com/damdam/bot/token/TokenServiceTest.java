package com.damdam.bot.token;

import com.damdam.bot.config.TossApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 가짜 서버로 토큰 발급을 흉내 내서, 저장된 토큰 파일의 실제 접근 권한을 확인한다 (실제 토스 API 호출 없음)
class TokenServiceTest {

	private TokenService serviceWritingTo(Path tokenFile) {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://toss.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("http://toss.test/oauth2/token"))
			.andRespond(withSuccess("{\"access_token\":\"fake-token\",\"token_type\":\"Bearer\",\"expires_in\":86400}",
				MediaType.APPLICATION_JSON));
		TossApiProperties properties = new TossApiProperties("http://toss.test", "dummy-key", "dummy-secret", tokenFile.toString());
		return new TokenService(builder.build(), properties, new ObjectMapper());
	}

	@Test
	void issuedTokenIsStoredAndReused(@TempDir Path tempDir) throws IOException {
		Path tokenFile = tempDir.resolve("data/token.json");
		TokenService service = serviceWritingTo(tokenFile);

		assertEquals("fake-token", service.getAccessToken());
		// 두 번째 호출은 서버에 다시 가지 않고 저장된 토큰을 재사용한다 (가짜 서버는 첫 요청만 기대함)
		assertEquals("fake-token", service.getAccessToken());
		assertTrue(Files.readString(tokenFile).contains("fake-token"));
	}

	// Windows에서 File.setReadable(false)가 아무 효과가 없었던 문제의 회귀 방지: 실제 파일 권한이 소유자 전용인지 확인한다
	@Test
	void tokenFileIsRestrictedToTheOwnerOnly(@TempDir Path tempDir) throws IOException {
		Path tokenFile = tempDir.resolve("token.json");
		serviceWritingTo(tokenFile).getAccessToken();

		PosixFileAttributeView posix = Files.getFileAttributeView(tokenFile, PosixFileAttributeView.class);
		if (posix != null) {
			assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()));
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(tokenFile, AclFileAttributeView.class);
		assertNotNull(acl, "POSIX도 ACL도 지원하지 않는 파일 시스템");
		List<AclEntry> entries = acl.getAcl();
		assertEquals(1, entries.size(), "소유자 항목 하나만 있어야 한다 (Users 같은 상속 항목이 없어야 함): " + entries);
		assertEquals(Files.getOwner(tokenFile), entries.get(0).principal());
		assertEquals(AclEntryType.ALLOW, entries.get(0).type());
	}

	// 이미 느슨한 권한으로 있던 파일도 다음 저장 때 소유자 전용으로 바뀐다
	@Test
	void anExistingLooseFileIsTightenedOnTheNextWrite(@TempDir Path tempDir) throws IOException {
		Path tokenFile = tempDir.resolve("token.json");
		Files.writeString(tokenFile, "{\"accessToken\":\"old\",\"tokenType\":\"Bearer\",\"issuedAt\":\"2020-01-01T00:00:00Z\",\"expiresInSeconds\":1}");

		assertEquals("fake-token", serviceWritingTo(tokenFile).getAccessToken());

		AclFileAttributeView acl = Files.getFileAttributeView(tokenFile, AclFileAttributeView.class);
		PosixFileAttributeView posix = Files.getFileAttributeView(tokenFile, PosixFileAttributeView.class);
		if (posix != null) {
			assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()));
		} else {
			assertEquals(1, acl.getAcl().size());
		}
	}
}
