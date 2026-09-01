package org.docx4j.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Shared test plumbing: a config rooted at the test resources plus a temp dir. */
final class TestSupport {

	static final Path RESOURCES = Path.of("src/test/resources").toAbsolutePath();

	private TestSupport() {}

	static ServerConfig config(Path... extraRoots) {
		List<Path> roots = new java.util.ArrayList<>(List.of(RESOURCES));
		roots.addAll(List.of(extraRoots));
		return new ServerConfig(new PathPolicy(roots), ServerConfig.DEFAULT_MAX_INLINE_CHARS, McpJsonDefaults.getMapper());
	}

	static ToolSupport.Args args(Object... kv) {
		Map<String, Object> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return new ToolSupport.Args(m);
	}

	static String text(CallToolResult r) {
		return ((TextContent) r.content().get(0)).text();
	}

	static String fixture(String name) {
		return RESOURCES.resolve(name).toString();
	}
}
