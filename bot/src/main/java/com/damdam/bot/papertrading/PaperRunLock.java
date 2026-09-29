package com.damdam.bot.papertrading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

// 가상매매 실행이 겹치지 않게 하는 잠금 파일 (운영체제 파일 잠금). 두 번 띄우거나 작업 스케줄러가 앞 실행이 끝나기 전에 다시 돌려도
// 같은 상태 파일을 동시에 고치지 않는다. 프로세스가 죽으면 운영체제가 잠금을 풀어서 죽은 실행이 잠금을 영원히 쥐지 않는다
public final class PaperRunLock implements AutoCloseable {

	private final FileChannel channel;
	private final FileLock lock;

	private PaperRunLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	// 이미 다른 실행이 잠금을 쥐고 있으면 빈 값을 돌려준다
	public static Optional<PaperRunLock> tryAcquire(Path file) {
		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try {
				FileLock lock = channel.tryLock();
				if (lock == null) {
					channel.close();
					return Optional.empty();
				}
				return Optional.of(new PaperRunLock(channel, lock));
			} catch (OverlappingFileLockException e) {
				// 같은 프로세스 안에서 이미 쥐고 있는 경우
				channel.close();
				return Optional.empty();
			}
		} catch (IOException e) {
			throw new UncheckedIOException("잠금 파일을 열지 못했습니다: " + file, e);
		}
	}

	// 아직 쥐고 있는지 (close한 뒤에는 false)
	public boolean isHeld() {
		return lock.isValid();
	}

	@Override
	public void close() {
		try {
			lock.release();
			channel.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
