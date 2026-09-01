package org.docx4j.mcp;

import java.nio.file.Path;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** describe_template: what data a template wants, so the agent can call fill_template without a human reading the docx. */
final class DescribeTemplateTool {

	static final String NAME = "describe_template";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "template_path": {"type": "string", "description": "Path to the .docx template (inside an allowed root)"}
			  },
			  "required": ["template_path"]
			}
			""";

	private DescribeTemplateTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Describe a docx template",
				"Inspects a Word template and reports what data it wants: for an OpenDoPE or content-control "
						+ "template, the skeleton XML to fill in (with the bound xpaths, repeats and conditions); "
						+ "for a mail-merge template, the MERGEFIELD names. Call this before fill_template.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path template = config.paths().resolveExisting("template_path", args.required("template_path"));
		WordprocessingMLPackage pkg = Docx4J.load(template.toFile());
		TemplateInspector.Description d = TemplateInspector.describe(pkg);
		return ToolSupport.json(config, d, Map.of("template_path", template.toString(), "kind", d.kind()));
	}
}
