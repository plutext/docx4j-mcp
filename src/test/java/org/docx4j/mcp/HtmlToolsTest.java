package org.docx4j.mcp;

import static org.docx4j.mcp.TestSupport.args;
import static org.docx4j.mcp.TestSupport.config;
import static org.docx4j.mcp.TestSupport.fixture;
import static org.docx4j.mcp.TestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.docx4j.Docx4J;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.AlternativeFormatInputPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class HtmlToolsTest {

	/** 1x1 red PNG. */
	private static final String PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

	private static final String LOOSE_HTML = """
			<h1>Quarterly Report</h1>
			<p>Revenue was <b>up 12%</b> on <i>last quarter</i>.<br>Second line.
			<ul><li>one<li>two</ul>
			<table border=1><tr><th>Item<th>Qty<tr><td>Apples<td>3</table>
			<p>Ends here
			""";

	@Test
	void looseHtmlIsConvertedToNativeWordContent(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("report.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("html", LOOSE_HTML, "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertEquals("convert", r.meta().get("mode"));
		assertTrue(((Number) r.meta().get("blocks")).intValue() >= 4, r.meta().toString());
		String t = ExtractTextTool.extract(out);
		assertTrue(t.contains("Quarterly Report") && t.contains("up 12%") && t.contains("Apples"), t);
		// native content, no altChunk
		WordprocessingMLPackage pkg = Docx4J.load(out.toFile());
		assertTrue(pkg.getParts().getParts().values().stream().noneMatch(p -> p instanceof AlternativeFormatInputPart));
		// round trip through markdown keeps the structure
		String md = text(DocxToMarkdownTool.run(config(tmp), args("input_path", out.toString())));
		assertTrue(md.contains("# Quarterly Report"), md);
		assertTrue(md.contains("**up 12%**"), md);
		assertTrue(md.contains("|Apples|3|"), md);
	}

	@Test
	void remoteImagesAreNotFetchedAndDataImagesAreKept(@TempDir Path tmp) throws Exception {
		String html = "<p>Logo: <img src=\"https://example.com/logo.png\" alt=\"logo\"></p>"
				+ "<p>Inline: <img src=\"data:image/png;base64," + PNG_B64 + "\"></p>"
				+ "<link rel=\"stylesheet\" href=\"https://example.com/site.css\">"
				+ "<script>alert(1)</script>";
		Path out = tmp.resolve("img.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("html", html, "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		List<String> warnings = (List<String>) r.meta().get("warnings");
		assertTrue(warnings.stream().anyMatch(w -> w.contains("logo.png")), warnings.toString());
		assertTrue(warnings.stream().anyMatch(w -> w.contains("site.css")), warnings.toString());
		WordprocessingMLPackage pkg = Docx4J.load(out.toFile());
		assertTrue(pkg.getParts().getParts().keySet().stream().anyMatch(n -> n.getName().contains("/media/")),
				"data: image should have become an image part: " + pkg.getParts().getParts().keySet());
	}

	@Test
	void localImageUnderRootIsResolvedAndOutsideRootIsDropped(@TempDir Path tmp) throws Exception {
		Path img = tmp.resolve("pic.png");
		Files.write(img, Base64.getDecoder().decode(PNG_B64));
		Path htmlFile = tmp.resolve("page.html");
		Files.writeString(htmlFile, "<p><img src=\"pic.png\"></p><p><img src=\"/etc/hostname\"></p>");
		Path out = tmp.resolve("local.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("input_path", htmlFile.toString(), "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		List<String> warnings = (List<String>) r.meta().get("warnings");
		assertEquals(1, warnings.size(), warnings.toString());
		assertTrue(warnings.get(0).contains("/etc/hostname"), warnings.toString());
		WordprocessingMLPackage pkg = Docx4J.load(out.toFile());
		assertTrue(pkg.getParts().getParts().keySet().stream().anyMatch(n -> n.getName().contains("/media/")));
	}

	@Test
	void altChunkModeEmbedsTheHtml(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("alt.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("html", LOOSE_HTML, "output_path", out.toString(), "mode", "altchunk"));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		WordprocessingMLPackage pkg = Docx4J.load(out.toFile());
		assertTrue(pkg.getParts().getParts().values().stream().anyMatch(p -> p instanceof AlternativeFormatInputPart));
	}

	@Test
	void stylesTemplateIsHonoured(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("styled.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("html", "<h1>Heading</h1><p>Body text.</p>",
				"styles_template_path", fixture("sample.docx"), "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String t = ExtractTextTool.extract(out);
		assertTrue(t.contains("Heading") && t.contains("Body text."), t);
		assertFalse(t.contains("Lorem ipsum"), t);
	}

	@Test
	void convertsDocxToHtml(@TempDir Path tmp) throws Exception {
		CallToolResult r = ConvertToHtmlTool.run(config(tmp), args("input_path", fixture("sample.docx")));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String html = text(r);
		assertTrue(html.contains("<html"), html.substring(0, 200));
		assertTrue(html.contains("Section 1"), "body text present");
		// to file with images
		Path out = tmp.resolve("sample.html");
		Path images = tmp.resolve("img");
		CallToolResult r2 = ConvertToHtmlTool.run(config(tmp), args("input_path", fixture("sample.docx"),
				"output_path", out.toString(), "image_dir_path", images.toString()));
		assertEquals(Boolean.FALSE, r2.isError(), text(r2));
		assertTrue(Files.exists(out));
		assertTrue(Files.readString(out).contains("img/"), "relative image references");
	}
}
