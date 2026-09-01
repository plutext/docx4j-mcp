package org.docx4j.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The path allow-list (CR §6): every path parameter, inputs and outputs alike, must
 * resolve (symlinks followed) inside one of the roots given at server start.  There is
 * no default root.
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

	/** Resolve an input path that must already exist as a regular file. */
	public Path resolveExisting(String param, String value) {
		Path p = resolve(param, value);
		if (!Files.isRegularFile(p)) {
			throw new ToolArgumentException(param + ": not a file: " + value);
		}
		return p;
	}

	/** Resolve a directory path, creating it if necessary. */
	public Path resolveDirectory(String param, String value) {
		Path p = resolve(param, value);
		if (Files.exists(p) && !Files.isDirectory(p)) {
			throw new ToolArgumentException(param + ": not a directory: " + value);
		}
		try {
			Files.createDirectories(p);
		} catch (IOException e) {
			throw new ToolArgumentException(param + ": cannot create directory " + value + ": " + e.getMessage());
		}
		return p;
	}

	/**
	 * Resolve an output path: under a root, not an existing file unless {@code overwrite},
	 * parent directories created.
	 */
	public Path resolveOutput(String param, String value, boolean overwrite) {
		Path p = resolve(param, value);
		if (Files.isDirectory(p)) {
			throw new ToolArgumentException(param + ": " + value + " is a directory");
		}
		if (Files.exists(p) && !overwrite) {
			throw new ToolArgumentException(param + ": " + value + " already exists; pass overwrite: true to replace it");
		}
		try {
			Files.createDirectories(p.getParent());
		} catch (IOException e) {
			throw new ToolArgumentException(param + ": cannot create parent directory of " + value + ": " + e.getMessage());
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
