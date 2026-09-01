package org.docx4j.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/** Tool logic tested directly (CR §8 phase 1 testing approach): no MCP client needed. */
class ExtractTextToolTest {

	private static final Path RESOURCES = Path.of("src/test/resources");
	private static final PathPolicy PATHS = new PathPolicy(List.of(RESOURCES));

	@Test
	void extractsParagraphText() throws Exception {
		String text = ExtractTextTool.extract(PATHS, Map.of("input_path", "src/test/resources/sample.docx"));
		assertTrue(text.startsWith("Title\n"), text);
		assertTrue(text.contains("\nSection 1\n"), text);
	}

	@Test
	void pathOutsideRootIsRejectedBeforeLoading() {
		ToolArgumentException e = assertThrows(ToolArgumentException.class,
				() -> ExtractTextTool.extract(PATHS, Map.of("input_path", "pom.xml")));
		assertTrue(e.getMessage().contains("outside the allowed roots"), e.getMessage());
	}

	@Test
	void handlerReportsBadArgumentsAsIsError() {
		CallToolResult r = ExtractTextTool.handler(PATHS).apply(null, new CallToolRequest("extract_text", Map.of()));
		assertEquals(Boolean.TRUE, r.isError());
		assertEquals("input_path is required", ((TextContent) r.content().get(0)).text());
	}
}
