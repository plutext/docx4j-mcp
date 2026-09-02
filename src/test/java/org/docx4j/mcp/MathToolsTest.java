package org.docx4j.mcp;

import static org.docx4j.mcp.TestSupport.args;
import static org.docx4j.mcp.TestSupport.config;
import static org.docx4j.mcp.TestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.docx4j.Docx4J;
import org.docx4j.XmlUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Math end to end: TeX in Markdown and MathML in HTML become OMML; OMML renders in PDF and HTML. */
class MathToolsTest {

	@BeforeAll
	static void warmUp() {
		Docx4jMcpServer.warmUp();
	}

	private static String mainPartXml(Path docx) throws Exception {
		return XmlUtils.marshaltoString(Docx4J.load(docx.toFile()).getMainDocumentPart().getJaxbElement());
	}

	@Test
	void texMathBecomesOmmlAndRendersInPdf(@TempDir Path tmp) throws Exception {
		String md = """
				# Physics

				Inline $E = mc^2$ and display:

				$$\\sum_{k=0}^{n} \\binom{n}{k} x^k = (1+x)^n$$
				""";
		Path docx = tmp.resolve("math.docx");
		CallToolResult r = MarkdownToDocxTool.run(config(tmp), args("markdown", md, "output_path", docx.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String xml = mainPartXml(docx);
		assertTrue(xml.contains("oMath"), "expected OMML in " + docx);

		Path pdf = tmp.resolve("math.pdf");
		CallToolResult p = ConvertToPdfTool.run(config(tmp), args("input_path", docx.toString(), "output_path", pdf.toString()));
		assertEquals(Boolean.FALSE, p.isError(), text(p));
		assertTrue(Files.size(pdf) > 3000, "PDF with rendered equations expected, got " + Files.size(pdf) + " bytes");

		// and back to markdown as TeX
		String back = text(DocxToMarkdownTool.run(config(tmp), args("input_path", docx.toString())));
		assertTrue(back.contains("$E = mc^{2}$") || back.contains("$E=mc^2$") || back.contains("mc^"), back);
	}

	@Test
	void mathMlInHtmlBecomesOmml(@TempDir Path tmp) throws Exception {
		String html = "<h1>Ratio</h1><p>The value <math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
				+ "<mfrac><mi>a</mi><mrow><mi>b</mi><mo>+</mo><mn>1</mn></mrow></mfrac></math> matters.</p>";
		Path docx = tmp.resolve("frac.docx");
		CallToolResult r = HtmlToDocxTool.run(config(tmp), args("html", html, "output_path", docx.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String xml = mainPartXml(docx);
		assertTrue(xml.contains("oMath"), "expected OMML from MathML; got no equation. Body:\n"
				+ xml.substring(0, Math.min(2000, xml.length())));
	}

	@Test
	void ommlBecomesMathMlInHtmlExport(@TempDir Path tmp) throws Exception {
		Path docx = tmp.resolve("m.docx");
		MarkdownToDocxTool.run(config(tmp), args("markdown", "Value: $\\frac{a}{b}$", "output_path", docx.toString()));
		CallToolResult r = ConvertToHtmlTool.run(config(tmp), args("input_path", docx.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		String html = text(r);
		assertTrue(html.contains("<math") || html.contains(":math"), "expected MathML in HTML output:\n"
				+ html.substring(0, Math.min(1500, html.length())));
		assertTrue(html.contains("mfrac"), "expected mfrac in HTML output");
	}
}
