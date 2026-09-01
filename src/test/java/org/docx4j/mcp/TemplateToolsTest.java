package org.docx4j.mcp;

import static org.docx4j.mcp.TestSupport.args;
import static org.docx4j.mcp.TestSupport.config;
import static org.docx4j.mcp.TestSupport.fixture;
import static org.docx4j.mcp.TestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** describe_template + fill_template on an OpenDoPE template and a MERGEFIELD template. */
class TemplateToolsTest {

	private static final String INVOICE_DATA = """
			<invoice>
			  <customer><name>Joe Bloggs</name></customer>
			  <items>
			    <item><name>apples</name><price>$20</price></item>
			    <item><name>bananas</name><price>$30</price></item>
			    <item><name>cherries</name><price>$40</price></item>
			  </items>
			  <misc>
			    <includeBankDetails>true</includeBankDetails>
			    <wantspam>false</wantspam>
			  </misc>
			</invoice>
			""";

	@SuppressWarnings("unchecked")
	private static Map<String, Object> describe(String fixture) throws Exception {
		CallToolResult r = DescribeTemplateTool.run(config(), args("template_path", fixture(fixture)));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		return config().mapper().readValue(text(r), Map.class);
	}

	@Test
	void describesOpenDoPETemplate() throws Exception {
		Map<String, Object> d = describe("invoice-opendope.docx");
		assertEquals("opendope", d.get("kind"));
		assertEquals("xml", d.get("data_format"));
		String skeleton = (String) d.get("skeleton_xml");
		assertNotNull(skeleton);
		assertTrue(skeleton.contains("<invoice>") && skeleton.contains("<name>"), skeleton);
		assertFalse(((List<?>) d.get("xpaths")).isEmpty());
		assertFalse(((List<?>) d.get("conditions")).isEmpty());
		List<Map<String, Object>> controls = (List<Map<String, Object>>) d.get("content_controls");
		assertTrue(controls.stream().anyMatch(c -> "repeat".equals(c.get("role"))), controls.toString());
		assertTrue(controls.stream().anyMatch(c -> "condition".equals(c.get("role"))), controls.toString());
		assertTrue(((List<?>) d.get("merge_fields")).isEmpty());
	}

	@Test
	void describesMailMergeTemplate() throws Exception {
		Map<String, Object> d = describe("mergefield.docx");
		assertEquals("mail_merge", d.get("kind"));
		assertEquals("json", d.get("data_format"));
		List<String> fields = (List<String>) d.get("merge_fields");
		assertFalse(fields.isEmpty(), "expected MERGEFIELDs");
		assertEquals(fields.size(), fields.stream().map(String::toUpperCase).distinct().count(), "case-variants deduplicated: " + fields);
		Map<String, String> formats = (Map<String, String>) d.get("merge_field_formats");
		assertTrue(formats.containsKey("yourdate"), formats.toString());
		assertTrue(formats.get("yourdate").contains("\\@"), formats.toString());
	}

	@Test
	void plainDocumentIsNotATemplate() throws Exception {
		Map<String, Object> d = describe("sample.docx");
		assertEquals("none", d.get("kind"));
		Map<String, Object> doc = (Map<String, Object>) d.get("document");
		assertTrue(((Number) doc.get("paragraphs")).intValue() > 5);
	}

	@Test
	void fillsOpenDoPETemplateWithRepeatsAndConditions(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("invoice.docx");
		CallToolResult r = FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("invoice-opendope.docx"),
				"data", INVOICE_DATA,
				"output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertEquals("opendope", r.meta().get("mode"));
		assertTrue(Files.size(out) > 0);
		String textOut = ExtractTextTool.extract(out);
		assertTrue(textOut.contains("Joe Bloggs"), textOut);
		assertTrue(textOut.contains("apples") && textOut.contains("cherries"), textOut);
	}

	@Test
	void refusesToOverwriteUnlessAsked(@TempDir Path tmp) throws Exception {
		Path out = tmp.resolve("x.docx");
		Files.writeString(out, "existing");
		ToolArgumentException e = assertThrows(ToolArgumentException.class, () -> FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("invoice-opendope.docx"), "data", INVOICE_DATA, "output_path", out.toString())));
		assertTrue(e.getMessage().contains("overwrite"), e.getMessage());
		CallToolResult r = FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("invoice-opendope.docx"), "data", INVOICE_DATA, "output_path", out.toString(),
				"overwrite", true));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
	}

	@Test
	void fillsMailMergeTemplateFromJsonObject(@TempDir Path tmp) throws Exception {
		Map<String, Object> d = describe("mergefield.docx");
		List<String> fields = (List<String>) d.get("merge_fields");
		Map<String, String> formats = (Map<String, String>) d.get("merge_field_formats");
		Map<String, Object> data = new LinkedHashMap<>();
		for (String f : fields) {
			data.put(f, !formats.containsKey(f) ? "VALUE_" + f.replaceAll("[^A-Za-z0-9]", "")
					: formats.get(f).contains("\\@") ? "1 September 2026" : "1234567");
		}
		data.put("NotAField", "x");
		Path out = tmp.resolve("merged.docx");
		CallToolResult r = FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("mergefield.docx"), "data", data, "output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertEquals("mail_merge", r.meta().get("mode"));
		assertEquals(List.of("NotAField"), r.meta().get("unused_keys"));
		String textOut = ExtractTextTool.extract(out);
		for (String f : fields) {
			if (!formats.containsKey(f)) {
				assertTrue(textOut.contains("VALUE_" + f.replaceAll("[^A-Za-z0-9]", "")), f + " not merged: " + textOut);
			}
		}
		assertTrue(textOut.contains("September 01, 2026"), "date formatted per \\@ switch: " + textOut);
		assertTrue(textOut.contains("$1,234,567"), "number formatted per \\# switch: " + textOut);
		assertFalse(textOut.contains("MERGEFIELD"), textOut);
	}

	@Test
	void mailMergeAlsoAcceptsJsonString(@TempDir Path tmp) throws Exception {
		List<String> fields = (List<String>) describe("mergefield.docx").get("merge_fields");
		Path out = tmp.resolve("merged2.docx");
		CallToolResult r = FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("mergefield.docx"),
				"data", "{\"" + fields.get(0) + "\": \"hello\"}",
				"output_path", out.toString()));
		assertEquals(Boolean.FALSE, r.isError(), text(r));
		assertEquals(fields.subList(1, fields.size()), r.meta().get("missing_fields"));
	}

	@Test
	void unparseableDateIsExplained(@TempDir Path tmp) {
		ToolArgumentException e = assertThrows(ToolArgumentException.class, () -> FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("mergefield.docx"), "data", Map.of("yourdate", "not a date"),
				"output_path", tmp.resolve("o.docx").toString())));
		assertTrue(e.getMessage().contains("yourdate"), e.getMessage());
	}

	@Test
	void wrongDataShapeIsExplained(@TempDir Path tmp) {
		ToolArgumentException e = assertThrows(ToolArgumentException.class, () -> FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("mergefield.docx"), "data", INVOICE_DATA, "output_path", tmp.resolve("o.docx").toString())));
		assertTrue(e.getMessage().contains("MERGEFIELD"), e.getMessage());
		ToolArgumentException e2 = assertThrows(ToolArgumentException.class, () -> FillTemplateTool.run(config(tmp), args(
				"template_path", fixture("invoice-opendope.docx"), "data", Map.of("a", "b"), "output_path", tmp.resolve("o.docx").toString())));
		assertTrue(e2.getMessage().contains("XML"), e2.getMessage());
	}
}
