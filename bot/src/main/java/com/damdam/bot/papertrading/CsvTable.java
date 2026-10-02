package com.damdam.bot.papertrading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 결정적 키로 중복을 무시하며 행을 더하는 CSV 파일. 같은 날을 다시 정산해도 같은 행이 두 번 쌓이지 않는다.
// 키는 앞쪽 keyColumns개 열이다 (쉼표, 따옴표, 줄바꿈이 없는 값이어야 한다). 매번 임시 파일에 통째로 쓴 뒤 교체해서
// 쓰다 죽어도 반쯤 쓴 행이 남지 않는다 (기록이 수백 행 규모라 통째로 쓰는 비용은 무시할 만하다)
public final class CsvTable {

	private final Path file;
	private final List<String> header;
	private final int keyColumns;

	public CsvTable(Path file, List<String> header, int keyColumns) {
		if (keyColumns < 1 || keyColumns > header.size()) {
			throw new IllegalArgumentException("키 열 수가 헤더 범위를 벗어났습니다: " + keyColumns);
		}
		this.file = file;
		this.header = List.copyOf(header);
		this.keyColumns = keyColumns;
	}

	// 파일에 실제로 더한 행 수를 돌려준다. 헤더가 다른 파일이 이미 있으면 덮어쓰지 않고 예외를 던진다.
	// 같은 키의 행이 이미 있으면 내용이 같을 때만 무시한다. 내용이 다르면(잠정치가 확정치로 바뀌었거나, 설정이 바뀐 채
	// 재실행한 경우) 어느 쪽이 맞는지 이 계층이 알 수 없으므로 조용히 넘기지 않고 예외를 던진다
	public int append(List<List<String>> rows) {
		if (rows.isEmpty()) {
			return 0;
		}
		try {
			List<String> lines = existingLines();
			Map<String, String> lineByKey = new HashMap<>();
			for (int i = 1; i < lines.size(); i++) {
				lineByKey.putIfAbsent(keyOfLine(lines.get(i)), lines.get(i));
			}
			List<String> added = new ArrayList<>();
			for (List<String> row : rows) {
				validate(row);
				String key = String.join(",", row.subList(0, keyColumns));
				String line = toLine(row);
				String existing = lineByKey.putIfAbsent(key, line);
				if (existing == null) {
					added.add(line);
				} else if (!existing.equals(line)) {
					throw new IllegalStateException("같은 키(%s)에 다른 내용의 행이 이미 있습니다 (%s). 기존: %s / 새 값: %s"
						.formatted(key, file.getFileName(), existing, line));
				}
			}
			if (added.isEmpty()) {
				return 0;
			}
			if (lines.isEmpty()) {
				lines.add(toLine(header));
			}
			lines.addAll(added);
			writeAtomically(lines);
			return added.size();
		} catch (IOException e) {
			throw new UncheckedIOException("CSV 기록에 실패했습니다: " + file, e);
		}
	}

	private List<String> existingLines() throws IOException {
		if (!Files.exists(file)) {
			return new ArrayList<>();
		}
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
			.filter(line -> !line.isBlank())
			.collect(Collectors.toCollection(ArrayList::new));
		if (lines.isEmpty()) {
			return lines;
		}
		// 엑셀이 UTF-8 CSV로 다시 저장하면 BOM이 붙는다
		String first = lines.get(0).startsWith("﻿") ? lines.get(0).substring(1) : lines.get(0);
		if (!first.equals(toLine(header))) {
			throw new IllegalStateException("CSV 헤더가 예상과 다릅니다 (덮어쓰지 않음): " + file);
		}
		lines.set(0, first);
		return lines;
	}

	private String keyOfLine(String line) {
		String[] parts = line.split(",", keyColumns + 1);
		return String.join(",", List.of(parts).subList(0, Math.min(keyColumns, parts.length)));
	}

	private void validate(List<String> row) {
		if (row.size() != header.size()) {
			throw new IllegalArgumentException("열 수가 헤더와 다릅니다: %d개 (헤더 %d개)".formatted(row.size(), header.size()));
		}
		for (String key : row.subList(0, keyColumns)) {
			if (key.contains(",") || key.contains("\"") || key.contains("\n") || key.contains("\r")) {
				throw new IllegalArgumentException("키 열의 값에 쉼표, 따옴표, 줄바꿈을 쓸 수 없습니다: " + key);
			}
		}
	}

	private static String toLine(List<String> fields) {
		return fields.stream().map(CsvTable::escape).collect(Collectors.joining(","));
	}

	private static String escape(String field) {
		if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
			return "\"" + field.replace("\"", "\"\"") + "\"";
		}
		return field;
	}

	private void writeAtomically(List<String> lines) throws IOException {
		Path absolute = file.toAbsolutePath();
		Files.createDirectories(absolute.getParent());
		Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
		Files.writeString(temp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
