package org.docx4j.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The path allow-list (CR §6): every path parameter must resolve (symlinks followed)
 * inside one of the roots given at server start.  There is no default root.
 */
public final class PathPolicy {

	private final List<Path> roots;

	public PathPolicy(List<Path> roots) {
		if (roots.isEmpty()) {
			throw new IllegalArgumentException("at least one --root is required");
		}
		this.roots = roots.stream().map(PathPolicy::realOrAbsolute).toList();
	}

	public List<Path> roots() {
		return roots;
	}

	/** Resolve an input path that must already exist. */
	public Path resolveExisting(String param, String value) {
		Path p = resolve(param, value);
		if (!Files.isRegularFile(p)) {
			throw new ToolArgumentException(param + ": not a file: " + value);
		}
		return p;
	}

	/** Resolve a path (existing or not) and check it is under a root. */
	public Path resolve(String param, String value) {
		if (value == null || value.isBlank()) {
			throw new ToolArgumentException(param + " is required");
		}
		Path p = realOrAbsolute(Path.of(value));
		for (Path root : roots) {
			if (p.startsWith(root)) {
				return p;
			}
		}
		throw new ToolArgumentException(param + ": " + value + " is outside the allowed roots " + roots);
	}

	private static Path realOrAbsolute(Path p) {
		Path abs = p.toAbsolutePath().normalize();
		try {
			// resolve symlinks where the path (or its nearest existing ancestor) exists
			Path probe = abs;
			while (probe != null && !Files.exists(probe)) {
				probe = probe.getParent();
			}
			if (probe == null) {
				return abs;
			}
			Path real = probe.toRealPath();
			return real.resolve(probe.relativize(abs));
		} catch (IOException e) {
			return abs;
		}
	}
}
