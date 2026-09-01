package org.docx4j.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.markdown.MarkdownImportIssue;
import org.docx4j.markdown.MarkdownImportOptions;
import org.docx4j.markdown.MarkdownImporter;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** markdown_to_docx via docx4j-markdown: real styles, numbering, tables, footnotes, math. */
final class MarkdownToDocxTool {

	static final String NAME = "markdown_to_docx";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "markdown": {"type": "string", "description": "The Markdown text (CommonMark + GFM tables, task lists, footnotes; $..$ TeX math)"},
			    "input_path": {"type": "string", "description": "Alternatively, path to a .md file"},
			    "styles_template_path": {"type": "string", "description": "Optional .docx whose styles, numbering, page setup and headers/footers the output should use; its body content is replaced"},
			    "output_path": {"type": "string", "description": "Where to write the .docx"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"}
			  },
			  "required": ["output_path"]
			}
			""";

	private MarkdownToDocxTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Convert Markdown to docx",
				"Creates a properly styled Word document from Markdown: headings, lists and numbering, GFM tables, "
						+ "code, footnotes, task lists and TeX math become native Word constructs (no HTML detour). "
						+ "Give a styles_template_path to inherit an organisation's styles. Remote images are not fetched.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		String source = args.oneOf("markdown", "input_path");
		String markdown = source.equals("markdown")
				? args.required("markdown")
				: Files.readString(config.paths().resolveExisting("input_path", args.required("input_path")), StandardCharsets.UTF_8);
		Path out = config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false));

		List<MarkdownImportIssue> issues = new ArrayList<>();
		MarkdownImporter importer = new MarkdownImporter(new MarkdownImportOptions().setIssueListener(issues::add));

		WordprocessingMLPackage pkg;
		int blocks;
		if (args.has("styles_template_path")) {
			Path tpl = config.paths().resolveExisting("styles_template_path", args.required("styles_template_path"));
			pkg = Docx4J.load(tpl.toFile());
			pkg.getMainDocumentPart().getContent().clear();
			blocks = importer.importToMainDocumentPart(markdown, pkg).size();
		} else {
			pkg = importer.createPackage(markdown);
			blocks = pkg.getMainDocumentPart().getContent().size();
		}
		pkg.save(out.toFile());

		long bytes = ToolSupport.sizeOf(out);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("output_path", out.toString());
		meta.put("bytes", bytes);
		meta.put("blocks", blocks);
		meta.put("issues", issues.stream().map(MarkdownImportIssue::toString).toList());
		StringBuilder sb = new StringBuilder("Wrote ").append(out).append(" (").append(bytes).append(" bytes, ")
				.append(blocks).append(" block-level elements).");
		if (!issues.isEmpty()) {
			sb.append(" Import issues (content kept literally): ");
			issues.forEach(i -> sb.append(i).append("; "));
		}
		return ToolSupport.text(sb.toString(), meta);
	}
}
