package org.docx4j.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** Shared plumbing for tools: argument access, result shapes, the inline cap, error mapping. */
final class ToolSupport {

	private static final Logger log = LoggerFactory.getLogger(ToolSupport.class);

	private ToolSupport() {}

	/** A tool body: receives parsed arguments, returns a result or throws. */
	@FunctionalInterface
	interface Body {
		CallToolResult call(Args args) throws Exception;
	}

	/** Build a tool spec whose handler maps exceptions to {@code isError} results. */
	static SyncToolSpecification spec(ServerConfig config, String name, String title, String description,
			String inputSchema, Body body) {
		Tool tool = Tool.builder()
				.name(name)
				.title(title)
				.description(description)
				.inputSchema(config.mapper(), inputSchema)
				.build();
		return SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> {
					try {
						return body.call(new Args(request.arguments()));
					} catch (ToolArgumentException e) {
						return error(e.getMessage());
					} catch (Exception e) {
						log.warn("{} failed", name, e);
						return error(name + " failed: " + rootMessage(e));
					}
				})
				.build();
	}

	static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}

	static CallToolResult text(String text, Map<String, Object> meta) {
		return CallToolResult.builder().addTextContent(text).isError(false).meta(meta).build();
	}

	/**
	 * Deliver text to the agent: inline when it fits under the cap; otherwise to
	 * {@code outputPath} if one was given, else truncated with a marker.  Always writes
	 * the full text when an output path is given.
	 */
	static CallToolResult deliverText(ServerConfig config, String text, Path outputPath, Map<String, Object> meta)
			throws IOException {
		Map<String, Object> m = new LinkedHashMap<>(meta);
		m.put("chars", text.length());
		if (outputPath != null) {
			Files.writeString(outputPath, text, StandardCharsets.UTF_8);
			m.put("output_path", outputPath.toString());
			if (text.length() > config.maxInlineChars()) {
				return text("Wrote " + text.length() + " chars to " + outputPath
						+ " (too large to return inline; limit " + config.maxInlineChars() + ")", m);
			}
			return text(text, m);
		}
		if (text.length() > config.maxInlineChars()) {
			m.put("truncated", true);
			return text(text.substring(0, config.maxInlineChars())
					+ "\n\n[TRUNCATED: " + text.length() + " chars total, inline limit " + config.maxInlineChars()
					+ ". Call again with output_path to get the whole result as a file.]", m);
		}
		return text(text, m);
	}

	/** A structured result: JSON text for the agent, plus the same object as structuredContent. */
	static CallToolResult json(ServerConfig config, Object value, Map<String, Object> meta) throws IOException {
		return CallToolResult.builder()
				.addTextContent(config.mapper().writeValueAsString(value))
				.structuredContent(value)
				.isError(false)
				.meta(meta)
				.build();
	}

	static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null && c.getCause() != c) {
			c = c.getCause();
		}
		return c == t ? String.valueOf(t.getMessage()) : t.getMessage() + " (caused by " + c + ")";
	}

	static long sizeOf(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return -1;
		}
	}

	/** Typed access to the tool's argument map. */
	static final class Args {
		private final Map<String, Object> map;

		Args(Map<String, Object> map) {
			this.map = map == null ? Map.of() : map;
		}

		Object raw(String name) {
			return map.get(name);
		}

		boolean has(String name) {
			Object v = map.get(name);
			return v != null && !(v instanceof String s && s.isBlank());
		}

		String required(String name) {
			String v = optional(name);
			if (v == null) {
				throw new ToolArgumentException(name + " is required");
			}
			return v;
		}

		String optional(String name) {
			Object v = map.get(name);
			if (v == null) {
				return null;
			}
			if (!(v instanceof String s)) {
				throw new ToolArgumentException(name + " must be a string");
			}
			return s.isBlank() ? null : s;
		}

		boolean bool(String name, boolean dflt) {
			Object v = map.get(name);
			if (v == null) {
				return dflt;
			}
			if (v instanceof Boolean b) {
				return b;
			}
			if (v instanceof String s) {
				return Boolean.parseBoolean(s);
			}
			throw new ToolArgumentException(name + " must be a boolean");
		}

		/** Exactly one of the named parameters must be present. */
		String oneOf(String... names) {
			String found = null;
			for (String n : names) {
				if (has(n)) {
					if (found != null) {
						throw new ToolArgumentException("give only one of " + List.of(names));
					}
					found = n;
				}
			}
			if (found == null) {
				throw new ToolArgumentException("one of " + List.of(names) + " is required");
			}
			return found;
		}
	}
}
