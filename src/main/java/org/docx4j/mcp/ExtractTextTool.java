package org.docx4j.mcp;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** extract_text: plain text of the main document part, one line per top-level block. Stateless per call. */
final class ExtractTextTool {

	static final String NAME = "extract_text";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "input_path": {"type": "string", "description": "Path to a .docx file, inside one of the server's allowed root directories"},
			    "output_path": {"type": "string", "description": "Optional: write the text to this file (required if the result exceeds the inline limit)"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"}
			  },
			  "required": ["input_path"]
			}
			""";

	private ExtractTextTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Extract text from a docx",
				"Returns the plain text of a Word document's body, one line per paragraph or table, with no "
						+ "structure. For headings, tables and notes as Markdown use docx_to_markdown instead.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path in = config.paths().resolveExisting("input_path", args.required("input_path"));
		Path out = args.has("output_path")
				? config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false))
				: null;
		return ToolSupport.deliverText(config, extract(in), out, Map.of("input_path", in.toString()));
	}

	static String extract(Path in) throws Exception {
		WordprocessingMLPackage pkg = Docx4J.load(in.toFile());
		StringWriter sw = new StringWriter();
		for (Object block : pkg.getMainDocumentPart().getContent()) {
			TextUtils.extractText(block, sw);
			sw.append('\n');
		}
		return sw.toString();
	}
}
