package com.damdam.bot.backtest;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 수집 러너의 옵션 읽기. 옵션을 아무것도 안 주면 기존 동작(빈 값)이어야 하고, 스프링이 넘기는 --spring... 인자와 섞이지 않아야 한다
class UniverseExportRunnerOptionsTest {

	@Test
	void missingOptionIsEmptySoTheCallerKeepsTheOldDefault() {
		String[] args = {"--spring.profiles.active=export-universe", "30"};
		assertEquals(Optional.empty(), UniverseExportRunner.parseOption(args, "--duration="));
		assertEquals(Optional.empty(), UniverseExportRunner.parseBooleanOption(args, "--exclude-caution="));
	}

	@Test
	void readsValuesAfterThePrefixAndIgnoresOtherArguments() {
		String[] args = {"--spring.profiles.active=export-universe", "100", "--duration=1d", "--exclude-caution=false", "--out-dir=../analysis/data/universe100"};
		assertEquals(Optional.of("1d"), UniverseExportRunner.parseOption(args, "--duration="));
		assertEquals(Optional.of(false), UniverseExportRunner.parseBooleanOption(args, "--exclude-caution="));
		assertEquals(Optional.of("../analysis/data/universe100"), UniverseExportRunner.parseOption(args, "--out-dir="));
	}

	@Test
	void aTypoInTheBooleanOptionFailsInsteadOfSilentlyBecomingFalse() {
		String[] args = {"--exclude-caution=flase"};
		assertThrows(IllegalArgumentException.class, () -> UniverseExportRunner.parseBooleanOption(args, "--exclude-caution="));
	}
}
