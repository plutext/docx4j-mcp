package org.docx4j.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.convert.out.HTMLSettings;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** convert_to_html via the visitor HTML exporter (XSLT pathway opt-in). */
final class ConvertToHtmlTool {

	static final String NAME = "convert_to_html";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "input_path": {"type": "string", "description": "Path to the .docx"},
			    "output_path": {"type": "string", "description": "Optional: write the HTML to this file (required if the result exceeds the inline limit)"},
			    "image_dir_path": {"type": "string", "description": "Optional directory to write images into (referenced relatively from the HTML when output_path is given); images are omitted otherwise"},
			    "use_xslt": {"type": "boolean", "description": "Use the older XSLT-based exporter instead of the default visitor exporter (default false)"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"}
			  },
			  "required": ["input_path"]
			}
			""";

	private ConvertToHtmlTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Convert docx to HTML",
				"Renders a Word document to a standalone HTML page (CSS from the document's styles, fonts "
						+ "mapped to web font stacks); Word equations become native, accessible MathML (no JavaScript). For a lossless-ish text view prefer docx_to_markdown; use this "
						+ "when the layout/formatting matters or the HTML will be shown in a browser.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path in = config.paths().resolveExisting("input_path", args.required("input_path"));
		Path out = args.has("output_path")
				? config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false))
				: null;

		WordprocessingMLPackage pkg = Docx4J.load(in.toFile());
		HTMLSettings settings = Docx4J.createHTMLSettings();
		settings.setWmlPackage(pkg);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("input_path", in.toString());
		if (args.has("image_dir_path")) {
			Path dir = config.paths().resolveDirectory("image_dir_path", args.required("image_dir_path"));
			settings.setImageDirPath(dir.toString());
			settings.setImageTargetUri(out != null ? out.getParent().relativize(dir).toString() : dir.toString());
			meta.put("image_dir_path", dir.toString());
		}
		int flags = args.bool("use_xslt", false) ? Docx4J.FLAG_EXPORT_PREFER_XSL : Docx4J.FLAG_EXPORT_PREFER_NONXSL;

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Docx4J.toHTML(settings, bos, flags);
		String html = bos.toString(StandardCharsets.UTF_8);
		return ToolSupport.deliverText(config, html, out, meta);
	}
}
