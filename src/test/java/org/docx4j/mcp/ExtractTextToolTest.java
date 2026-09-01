package org.docx4j.mcp;

import static org.docx4j.mcp.TestSupport.args;
import static org.docx4j.mcp.TestSupport.config;
import static org.docx4j.mcp.TestSupport.fixture;
import static org.docx4j.mcp.TestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ExtractTextToolTest {

	@Test
	void extractsParagraphText() throws Exception {
		CallToolResult r = ExtractTextTool.run(config(), args("input_path", fixture("sample.docx")));
		assertEquals(Boolean.FALSE, r.isError());
		String t = text(r);
		assertTrue(t.startsWith("Title\n"), t);
		assertTrue(t.contains("\nSection 1\n"), t);
	}

	@Test
	void pathOutsideRootIsRejected() {
		ToolArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(ToolArgumentException.class,
				() -> ExtractTextTool.run(config(), args("input_path", "pom.xml")));
		assertTrue(e.getMessage().contains("outside the allowed roots"), e.getMessage());
	}

	@Test
	void specHandlerReportsBadArgumentsAsIsError() {
		ServerConfig c = config();
		CallToolResult r = ExtractTextTool.spec(c).callHandler()
				.apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("extract_text", java.util.Map.of()));
		assertEquals(Boolean.TRUE, r.isError());
		assertEquals("input_path is required", text(r));
	}

	@Test
	void largeResultGoesToOutputPathWhenCapped(@TempDir Path tmp) throws Exception {
		ServerConfig small = new ServerConfig(config(tmp).paths(), 200, config().mapper());
		Path out = tmp.resolve("text.txt");
		CallToolResult r = ExtractTextTool.run(small, args("input_path", fixture("sample.docx"), "output_path", out.toString()));
		assertTrue(text(r).startsWith("Wrote"), text(r));
		assertTrue(Files.size(out) > 200);
		// and truncated inline when no output_path
		CallToolResult r2 = ExtractTextTool.run(small, args("input_path", fixture("sample.docx")));
		assertTrue(text(r2).contains("[TRUNCATED"), text(r2));
		assertEquals(Boolean.TRUE, r2.meta().get("truncated"));
	}
}
