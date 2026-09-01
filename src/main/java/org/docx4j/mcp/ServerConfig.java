package org.docx4j.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonMapper;

/** Server-wide settings: the path allow-list, the inline result cap, and the JSON mapper. */
public record ServerConfig(PathPolicy paths, int maxInlineChars, McpJsonMapper mapper) {

	public static final int DEFAULT_MAX_INLINE_CHARS = 200_000;

	/** Parsed command line: {@code --root DIR [--root DIR ...] [--max-inline-chars N]}. */
	public record Args(List<Path> roots, int maxInlineChars) {

		public static Args parse(String[] argv) {
			List<Path> roots = new ArrayList<>();
			int max = DEFAULT_MAX_INLINE_CHARS;
			for (int i = 0; i < argv.length; i++) {
				switch (argv[i]) {
					case "--root" -> roots.add(Path.of(value(argv, ++i, "--root needs a directory")));
					case "--max-inline-chars" -> {
						try {
							max = Integer.parseInt(value(argv, ++i, "--max-inline-chars needs a number"));
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("--max-inline-chars needs a number");
						}
						if (max < 1000) throw new IllegalArgumentException("--max-inline-chars must be at least 1000");
					}
					default -> throw new IllegalArgumentException("unknown argument: " + argv[i]);
				}
			}
			return new Args(roots, max);
		}

		private static String value(String[] argv, int i, String error) {
			if (i >= argv.length) throw new IllegalArgumentException(error);
			return argv[i];
		}
	}
}
