package org.docx4j.mcp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.markdown.MarkdownExportOptions;
import org.docx4j.markdown.MarkdownExporter;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** docx_to_markdown: the structured reader tool (headings, tables, footnotes survive). */
final class DocxToMarkdownTool {

	static final String NAME = "docx_to_markdown";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "input_path": {"type": "string", "description": "Path to the .docx"},
			    "output_path": {"type": "string", "description": "Optional: write the Markdown to this file (required if the result exceeds the inline limit)"},
			    "image_dir_path": {"type": "string", "description": "Optional directory to extract images into (they are referenced from the Markdown); images are dropped otherwise"},
			    "tracked_changes": {"type": "string", "enum": ["accept", "markup"], "description": "How to render tracked changes: accept them (default) or show insertions/deletions as markup"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"}
			  },
			  "required": ["input_path"]
			}
			""";

	private DocxToMarkdownTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Convert docx to Markdown",
				"Reads a Word document as Markdown (CommonMark + GFM): headings, lists, tables, footnotes, links "
						+ "and math are preserved as Markdown structure. Prefer this over extract_text when structure "
						+ "matters. Large results are truncated inline unless output_path is given.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path in = config.paths().resolveExisting("input_path", args.required("input_path"));
		Path out = args.has("output_path")
				? config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false))
				: null;

		MarkdownExportOptions options = new MarkdownExportOptions();
		String tracked = args.optional("tracked_changes");
		if (tracked != null) {
			switch (tracked) {
				case "accept" -> options.setTrackedChangesPolicy(MarkdownExportOptions.TrackedChangesPolicy.ACCEPT);
				case "markup" -> options.setTrackedChangesPolicy(MarkdownExportOptions.TrackedChangesPolicy.MARKUP);
				default -> throw new ToolArgumentException("tracked_changes must be 'accept' or 'markup'");
			}
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("input_path", in.toString());
		if (args.has("image_dir_path")) {
			Path dir = config.paths().resolveDirectory("image_dir_path", args.required("image_dir_path"));
			options.setImageDirPath(dir.toString());
			options.setImageTargetUri(out != null ? out.getParent().relativize(dir).toString() : dir.toString());
			meta.put("image_dir_path", dir.toString());
		}

		WordprocessingMLPackage pkg = Docx4J.load(in.toFile());
		String markdown = new MarkdownExporter(options).export(pkg);
		return ToolSupport.deliverText(config, markdown, out, meta);
	}
}
