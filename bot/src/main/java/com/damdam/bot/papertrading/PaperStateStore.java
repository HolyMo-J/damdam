package com.damdam.bot.papertrading;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

// 전략별 상태를 JSON 파일로 저장하고 읽는다 (파일 하나가 전략 하나, state_{전략}.json).
// 실패 시 닫히는(fail-closed) 방식이다: 파일이 있는데 못 읽으면 예외를 던진다. 조용히 초기 상태로 돌리면 보유 포지션과
// 청산 이력이 사라져 주간 손실 한도와 노출 한도 판정이 틀어지기 때문이다. 파일이 아예 없는 것은 처음 실행으로 본다
public final class PaperStateStore {

	private static final Pattern STRATEGY_NAME = Pattern.compile("[A-Za-z0-9]{1,16}");

	private final Path directory;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public PaperStateStore(Path directory) {
		this.directory = directory;
	}

	public PaperStrategyState load(String strategy) {
		Path file = fileOf(strategy);
		if (!Files.exists(file)) {
			return PaperStrategyState.initial(strategy);
		}
		try {
			PaperStrategyState state = objectMapper.readValue(file.toFile(), PaperStrategyState.class);
			if (!strategy.equals(state.strategy())) {
				throw new IllegalStateException("상태 파일 %s의 전략(%s)이 요청한 전략(%s)과 다릅니다.".formatted(file, state.strategy(), strategy));
			}
			return state;
		} catch (JacksonException e) {
			// 원인 메시지에 파일 내용이 섞일 수 있어 위치 정보만 남긴다. 파일은 사용자가 직접 확인한다
			throw new IllegalStateException("상태 파일을 읽지 못했습니다. 파일을 확인하세요 (직전 확정 상태 사본은 같은 폴더의 .bak): "
				+ file + " (" + e.getOriginalMessage() + ")");
		}
	}

	// 임시 파일에 쓰고 디스크에 내린(fsync) 뒤 교체해서, 쓰는 도중 종료돼도 깨진 파일이 남지 않게 한다.
	// 교체 직전에 기존 파일을 .bak으로 한 벌 남긴다 (전원 차단으로 새 파일이 손상돼도 직전 확정 상태를 손으로 복구할 수 있다.
	// 자동으로 .bak을 읽지는 않는다: 오래된 상태로 조용히 되돌아가면 안 되기 때문이다)
	public void save(PaperStrategyState state) {
		Path file = fileOf(state.strategy());
		try {
			Files.createDirectories(directory);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				channel.write(ByteBuffer.wrap(json));
				channel.force(true);
			}
			if (Files.exists(file)) {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("상태 파일을 저장하지 못했습니다: " + file, e);
		}
	}

	private Path fileOf(String strategy) {
		requireStrategyName(strategy);
		return directory.resolve("state_" + strategy + ".json");
	}

	// 전략 이름이 파일 경로에 들어가므로 경로를 벗어나는 값을 막는다
	static void requireStrategyName(String strategy) {
		if (strategy == null || !STRATEGY_NAME.matcher(strategy).matches()) {
			throw new IllegalArgumentException("전략 이름은 영문자와 숫자 1~16자여야 합니다: " + strategy);
		}
	}
}
