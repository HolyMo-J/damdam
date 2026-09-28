package com.damdam.bot.papertrading;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// 가상매매 코드가 실제 주문 경로에 닿지 않는지 지키는 회귀 가드 (docs/strategy.md "안전: 실제 주문 경로 원천 차단").
// 컴파일 시점 강제가 아니라 소스 텍스트 검사라서, 문자열을 조립한 리플렉션 같은 의도적 우회까지 막지는 못한다.
// 실수로 주문 코드에 의존하게 되는 것과, HTTP 쓰기 호출을 직접 만드는 것을 알아채는 용도다
class PaperTradingIsolationTest {

	private static final Path MAIN_SOURCE = Path.of("src/main/java/com/damdam/bot");
	private static final String PAPERTRADING = "papertrading";

	// 주문 경로에 닿는 패키지. liquidation(청산 봇, 내부에서 주문 서비스 호출)과 orderevent(주문 체결 스트림)도 함께 막는다
	private static final Set<String> FORBIDDEN_PACKAGES = Set.of("orders", "conditionalorder", "liquidation", "orderevent");

	// papertrading이 직접 import해도 되는 패키지. 새 의존이 필요하면 이 목록을 일부러 고치게 만드는 것이 목적이다
	// (market: 캔들, ranking: 순위 조회, stocks: 종목 정보 조회. 셋 다 조회 전용)
	private static final Set<String> ALLOWED_PACKAGES = Set.of(PAPERTRADING, "market", "ranking", "stocks");

	// papertrading은 HTTP 클라이언트를 직접 잡지 않고 조회 서비스만 쓴다. 쓰기 메서드 호출과 문자열 기반 빈/클래스 접근도 막는다
	private static final List<String> FORBIDDEN_TOKENS = List.of(
		"RestClient", "RestTemplate", "WebClient", "HttpClient", "HttpURLConnection",
		".post(", ".put(", ".delete(", ".patch(",
		"ApplicationContext", "BeanFactory", "Class.forName");

	private static final Pattern BOT_PACKAGE_REFERENCE = Pattern.compile("com\\.damdam\\.bot\\.(\\w+)");

	@Test
	void papertradingImportsOnlyAllowedPackages() throws IOException {
		List<Path> sources = javaFiles(MAIN_SOURCE.resolve(PAPERTRADING));
		// 경로가 바뀌어 검사 대상이 0개가 되면 테스트가 조용히 통과하므로, 대상이 있다는 것부터 확인한다
		assertFalse(sources.isEmpty(), "papertrading 소스를 찾지 못했습니다: " + MAIN_SOURCE.resolve(PAPERTRADING).toAbsolutePath());

		for (Path source : sources) {
			Set<String> unexpected = referencedPackages(Files.readString(source));
			unexpected.removeAll(ALLOWED_PACKAGES);
			assertEquals(Set.of(), unexpected, source.getFileName() + " 이(가) 허용 목록 밖의 패키지를 참조합니다");
		}
	}

	@Test
	void papertradingSourcesUseNoHttpClientsWriteCallsOrStringBasedBeanAccess() throws IOException {
		for (Path source : javaFiles(MAIN_SOURCE.resolve(PAPERTRADING))) {
			assertEquals(List.of(), findForbiddenTokens(Files.readString(source)),
				source.getFileName() + " 이(가) 금지된 호출을 포함합니다");
		}
	}

	@Test
	void packageDependenciesReachableFromPapertradingNeverTouchOrderPackages() throws IOException {
		// 패키지 단위 전이 의존. 파일 단위가 아니라 패키지 단위라 거칠지만, 통과하면 허용 패키지가 뒤에서 주문 코드를 끌고 오지 않는다는 뜻이다
		Map<String, Set<String>> graph = dependencyGraph();
		Set<String> reachable = new TreeSet<>();
		Deque<String> queue = new ArrayDeque<>(List.of(PAPERTRADING));
		Set<String> visited = new HashSet<>(queue);
		while (!queue.isEmpty()) {
			String pkg = queue.poll();
			reachable.add(pkg);
			for (String next : graph.getOrDefault(pkg, Set.of())) {
				if (visited.add(next)) {
					queue.add(next);
				}
			}
		}

		Set<String> touched = new TreeSet<>(reachable);
		touched.retainAll(FORBIDDEN_PACKAGES);
		assertEquals(Set.of(), touched, "papertrading에서 닿는 패키지: " + reachable);
	}

	@Test
	void detectorsFindViolationsAndIgnoreLookalikes() {
		assertEquals(Set.of("orders"), referencedPackages("import com.damdam.bot.orders.OrderService;"));
		assertEquals(Set.of("conditionalorder", "market"), referencedPackages(
			"var x = com.damdam.bot.conditionalorder.Foo.class;\nimport com.damdam.bot.market.Candle;"));
		assertEquals(Set.of("liquidation"), referencedPackages("package com.damdam.bot.liquidation;"));
		assertEquals(Set.of("ordersummary"), referencedPackages("import com.damdam.bot.ordersummary.Foo;"));
		assertFalse(FORBIDDEN_PACKAGES.contains("ordersummary"));

		assertEquals(List.of("RestClient", ".post("), findForbiddenTokens("private RestClient c; c.post().uri(x);"));
		assertEquals(List.of("ApplicationContext"), findForbiddenTokens("ctx = ApplicationContext.class"));
		assertEquals(List.of(), findForbiddenTokens("var post = candles.stream().toList();"));
	}

	private static List<Path> javaFiles(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	private static Set<String> referencedPackages(String content) {
		Set<String> packages = new TreeSet<>();
		Matcher matcher = BOT_PACKAGE_REFERENCE.matcher(content);
		while (matcher.find()) {
			packages.add(matcher.group(1));
		}
		return packages;
	}

	private static List<String> findForbiddenTokens(String content) {
		return FORBIDDEN_TOKENS.stream().filter(content::contains).toList();
	}

	// 각 패키지 폴더의 소스가 참조하는 다른 com.damdam.bot.* 패키지 (자기 자신은 제외)
	private static Map<String, Set<String>> dependencyGraph() throws IOException {
		Map<String, Set<String>> graph = new HashMap<>();
		for (Path source : javaFiles(MAIN_SOURCE)) {
			Path relative = MAIN_SOURCE.relativize(source);
			if (relative.getNameCount() < 2) {
				continue; // 최상위(BotApplication 등)는 패키지 폴더가 아니다
			}
			String pkg = relative.getName(0).toString();
			Set<String> referenced = referencedPackages(Files.readString(source));
			referenced.remove(pkg);
			graph.computeIfAbsent(pkg, k -> new HashSet<>()).addAll(referenced);
		}
		return graph;
	}
}
