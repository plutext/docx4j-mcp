package org.docx4j.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPolicyTest {

	@Test
	void requiresAtLeastOneRoot() {
		assertThrows(IllegalArgumentException.class, () -> new PathPolicy(List.of()));
	}

	@Test
	void dotDotCannotEscapeRoot(@TempDir Path root) {
		PathPolicy p = new PathPolicy(List.of(root));
		assertThrows(ToolArgumentException.class, () -> p.resolve("x", root.resolve("../escape.docx").toString()));
	}

	@Test
	void symlinkPointingOutsideRootIsRejected(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
		Path target = Files.writeString(elsewhere.resolve("secret.docx"), "x");
		Path link = root.resolve("link.docx");
		Files.createSymbolicLink(link, target);
		PathPolicy p = new PathPolicy(List.of(root));
		assertThrows(ToolArgumentException.class, () -> p.resolveExisting("x", link.toString()));
	}

	@Test
	void nonExistentOutputPathUnderRootIsAccepted(@TempDir Path root) throws Exception {
		PathPolicy p = new PathPolicy(List.of(root));
		Path out = p.resolve("output_path", root.resolve("new/out.docx").toString());
		assertEquals(root.toRealPath(), out.getParent().getParent());
	}
}
