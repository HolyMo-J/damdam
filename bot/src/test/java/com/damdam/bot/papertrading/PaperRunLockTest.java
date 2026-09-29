package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperRunLockTest {

	@TempDir
	Path dir;

	@Test
	void secondAcquireFailsWhileTheFirstIsHeldAndSucceedsAfterItIsClosed() {
		Path file = dir.resolve("nested/paper.lock");

		Optional<PaperRunLock> first = PaperRunLock.tryAcquire(file);
		assertTrue(first.isPresent());
		assertTrue(first.get().isHeld());
		assertTrue(PaperRunLock.tryAcquire(file).isEmpty());

		first.get().close();
		assertFalse(first.get().isHeld());

		Optional<PaperRunLock> third = PaperRunLock.tryAcquire(file);
		assertTrue(third.isPresent());
		third.get().close();
	}

	@Test
	void differentLockFilesDoNotBlockEachOther() {
		Optional<PaperRunLock> a = PaperRunLock.tryAcquire(dir.resolve("a.lock"));
		Optional<PaperRunLock> b = PaperRunLock.tryAcquire(dir.resolve("b.lock"));

		assertTrue(a.isPresent());
		assertTrue(b.isPresent());
		a.get().close();
		b.get().close();
	}
}
