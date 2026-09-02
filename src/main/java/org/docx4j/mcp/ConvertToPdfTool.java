package org.docx4j.mcp;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.fonts.Mapper;
import org.docx4j.fonts.PhysicalFont;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** convert_to_pdf via docx4j-export-fo (visitor exporter by default), reporting font substitutions. */
final class ConvertToPdfTool {

	static final String NAME = "convert_to_pdf";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "input_path": {"type": "string", "description": "Path to the .docx"},
			    "output_path": {"type": "string", "description": "Where to write the PDF"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"},
			    "use_xslt": {"type": "boolean", "description": "Use the older XSLT-based exporter instead of the default visitor exporter (default false)"}
			  },
			  "required": ["input_path", "output_path"]
			}
			""";

	private ConvertToPdfTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Convert docx to PDF",
				"Renders a Word document to PDF with docx4j's XSL-FO exporter (Apache FOP). Equations (OMML) are "
						+ "rendered (a very long single display equation will not line-wrap). Reports which fonts were "
						+ "substituted, so you can tell the user if the output may differ from Word.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path in = config.paths().resolveExisting("input_path", args.required("input_path"));
		Path out = config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false));

		WordprocessingMLPackage pkg = Docx4J.load(in.toFile());
		Mapper mapper = pkg.getFontMapper(); // default mapper; populates from fontsInUse()
		Map<String, String> substituted = new LinkedHashMap<>();
		List<String> unmapped = new ArrayList<>();
		for (String font : pkg.getMainDocumentPart().fontsInUse()) {
			PhysicalFont pf = mapper.get(font);
			if (pf == null) {
				unmapped.add(font);
			} else if (!font.equalsIgnoreCase(pf.getName())) {
				substituted.put(font, pf.getName());
			}
		}

		int flags = args.bool("use_xslt", false) ? Docx4J.FLAG_EXPORT_PREFER_XSL : Docx4J.FLAG_EXPORT_PREFER_NONXSL;
		try (OutputStream os = Files.newOutputStream(out)) {
			Docx4J.toPDF(pkg, os, flags);
		}

		long bytes = ToolSupport.sizeOf(out);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("input_path", in.toString());
		meta.put("output_path", out.toString());
		meta.put("bytes", bytes);
		meta.put("font_substitutions", substituted);
		meta.put("fonts_unmapped", unmapped);

		StringBuilder sb = new StringBuilder("Wrote ").append(out).append(" (").append(bytes).append(" bytes).");
		if (!substituted.isEmpty()) {
			sb.append(" Font substitutions: ");
			substituted.forEach((k, v) -> sb.append(k).append(" -> ").append(v).append("; "));
		}
		if (!unmapped.isEmpty()) {
			sb.append(" Fonts with no physical font available (renderer default used): ").append(unmapped).append('.');
		}
		return ToolSupport.text(sb.toString(), meta);
	}
}
