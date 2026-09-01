package org.docx4j.mcp;

import static org.docx4j.mcp.TestSupport.args;
import static org.docx4j.mcp.TestSupport.config;
import static org.docx4j.mcp.TestSupport.fixture;
import static org.docx4j.mcp.TestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ConversionToolsTest {

	@BeforeAll
	static void warmUp() {
		Docx4jMcpServer.warmUp(); // fonts, as the server would
	}

	@Test
	void convertsToPdfAndReportsFontSubstitutions(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("sample.pdf");
		CallToolResult r = ConvertToPdfTool.run(config(tmp), args("input_path", fixture("sample.docx"), "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		byte[] head = new byte[5];
		try (var in = Files.newInputStream(out)) {
			assertEquals(5, in.read(head));
		}
		assertEquals("%PDF-", new String(head, "US-ASCII"));
		assertTrue(r.meta().get("font_substitutions") instanceof Map, r.meta().toString());
		System.out.println("PDF: " + text(r));
	}

	@Test
	void markdownRoundTrip(@TempDir Path tmp) throws Exception {
		String md = """
				# Report Title

				Some **bold** and *italic* text with a footnote.[^1]

				## Data

				| Item | Qty |
				|------|-----|
				| Apples | 3 |
				| Pears | 5 |

				- one
				- two

				[^1]: The footnote.
				""";
		Path docx = tmp.resolve("report.docx");
		CallToolResult r = MarkdownToDocxTool.run(config(tmp), args("markdown", md, "output_path", docx.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertTrue(Files.size(docx) > 0);

		CallToolResult back = DocxToMarkdownTool.run(config(tmp), args("input_path", docx.toString()));
		assertEquals(Boolean.FALSE, back.isError(), text(back));
		String out = text(back);
		assertTrue(out.contains("# Report Title"), out);
		assertTrue(out.contains("**bold**"), out);
		assertTrue(out.contains("|Apples|3|"), out);
		assertTrue(out.contains("[^1]"), out);
	}

	@Test
	void markdownWithStylesTemplateKeepsTemplateStylesAndReplacesBody(@TempDir Path tmp) throws Exception {
		Path docx = tmp.resolve("styled.docx");
		CallToolResult r = MarkdownToDocxTool.run(config(tmp), args(
				"markdown", "# Heading\n\nBody text.",
				"styles_template_path", fixture("sample.docx"),
				"output_path", docx.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String t = ExtractTextTool.extract(docx);
		assertTrue(t.contains("Heading") && t.contains("Body text."), t);
		assertTrue(!t.contains("Lorem ipsum"), "template body should have been replaced: " + t);
	}

	@Test
	void docxToMarkdownWritesImagesAndFile(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("sample.md");
		Path images = tmp.resolve("img");
		CallToolResult r = DocxToMarkdownTool.run(config(tmp), args(
				"input_path", fixture("sample.docx"), "output_path", out.toString(), "image_dir_path", images.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertTrue(Files.exists(out));
		String md = Files.readString(out);
		assertTrue(md.contains("# ") || md.contains("Section 1"), md);
		assertTrue(Files.isDirectory(images));
	}
}
