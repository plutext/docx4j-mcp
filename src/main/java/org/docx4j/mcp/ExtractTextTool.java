package org.docx4j.mcp;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

import org.docx4j.Docx4J;
import org.docx4j.TextUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Phase 0 toy tool: plain text of a docx, one line per top-level block.
 * Stateless per call (CR §4): load, extract, drop.
 */
public final class ExtractTextTool {

	private static final Logger log = LoggerFactory.getLogger(ExtractTextTool.class);

	static final String NAME = "extract_text";

	private static final String INPUT_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "input_path": {
			      "type": "string",
			      "description": "Path to a .docx file, inside one of the server's allowed root directories"
			    }
			  },
			  "required": ["input_path"]
			}
			""";

	private ExtractTextTool() {}

	public static SyncToolSpecification spec(PathPolicy paths, McpJsonMapper mapper) {
		Tool tool = Tool.builder()
				.name(NAME)
				.title("Extract text from a docx")
				.description("Returns the plain text of a Word document (main document part), one line per paragraph or table. "
						+ "Use it to read a docx without vision tokens.")
				.inputSchema(mapper, INPUT_SCHEMA)
				.build();
		return SyncToolSpecification.builder()
				.tool(tool)
				.callHandler(handler(paths))
				.build();
	}

	static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler(PathPolicy paths) {
		return (exchange, request) -> {
			try {
				return CallToolResult.builder()
						.addTextContent(extract(paths, request.arguments()))
						.isError(false)
						.build();
			} catch (ToolArgumentException e) {
				return error(e.getMessage());
			} catch (Exception e) {
				log.warn("{} failed", NAME, e);
				return error(NAME + " failed: " + e);
			}
		};
	}

	/** The logic, callable from tests without an MCP client. */
	static String extract(PathPolicy paths, Map<String, Object> args) throws Exception {
		Path in = paths.resolveExisting("input_path", (String) args.get("input_path"));
		WordprocessingMLPackage pkg = Docx4J.load(in.toFile());
		MainDocumentPart mdp = pkg.getMainDocumentPart();
		StringWriter sw = new StringWriter();
		for (Object block : mdp.getContent()) {
			TextUtils.extractText(block, sw);
			sw.append('\n');
		}
		return sw.toString();
	}

	private static CallToolResult error(String message) {
		return CallToolResult.builder().addTextContent(message).isError(true).build();
	}
}
