package org.docx4j.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.model.fields.merge.DataFieldName;
import org.docx4j.model.fields.merge.MailMerger;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** fill_template: OpenDoPE / bound content controls from XML, or MERGEFIELDs from a JSON object. */
final class FillTemplateTool {

	static final String NAME = "fill_template";

	static {
		// Server-wide, set once: agents want a finished document, not live field codes.
		MailMerger.setMERGEFIELDInOutput(MailMerger.OutputField.REMOVED);
	}

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "template_path": {"type": "string", "description": "Path to the .docx template"},
			    "data": {
			      "type": ["string", "object"],
			      "description": "For OpenDoPE / content-control templates: the filled-in XML document as a string (start from describe_template's skeleton_xml). For mail-merge templates: a JSON object mapping MERGEFIELD names to values."
			    },
			    "output_path": {"type": "string", "description": "Where to write the filled .docx"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"},
			    "remove_sdts": {"type": "boolean", "description": "XML mode: strip the content-control wrappers from the result (default true)"},
			    "remove_xml": {"type": "boolean", "description": "XML mode: drop the custom XML data part from the result (default true)"},
			    "headers_footers": {"type": "boolean", "description": "Mail-merge mode: also merge fields in headers and footers (default true)"}
			  },
			  "required": ["template_path", "data", "output_path"]
			}
			""";

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {};

	private FillTemplateTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Fill a docx template",
				"Fills a Word template deterministically, preserving all its formatting. OpenDoPE and bound "
						+ "content-control templates take 'data' as an XML string (repeats and conditions are "
						+ "processed); mail-merge templates take 'data' as a JSON object of MERGEFIELD name to value. "
						+ "Use describe_template first to learn which, and what the data should look like.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		Path template = config.paths().resolveExisting("template_path", args.required("template_path"));
		Path out = config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false));
		Object data = args.raw("data");
		if (data == null) {
			throw new ToolArgumentException("data is required");
		}

		WordprocessingMLPackage pkg = Docx4J.load(template.toFile());
		Map<String, String> mergeFieldMap = TemplateInspector.mergeFields(pkg);
		List<String> mergeFields = new ArrayList<>(mergeFieldMap.keySet());
		boolean hasXpaths = pkg.getMainDocumentPart().getXPathsPart() != null;
		boolean hasCustomXml = TemplateInspector.skeletonXml(pkg) != null;

		Map<String, Object> json = null;
		String xml = null;
		if (data instanceof Map<?, ?> m) {
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) m;
			json = cast;
		} else if (data instanceof String s) {
			String t = s.strip();
			if (t.startsWith("<")) {
				xml = t;
			} else {
				try {
					json = config.mapper().readValue(t, MAP);
				} catch (Exception e) {
					throw new ToolArgumentException("data must be an XML document (string starting with '<') or a JSON object");
				}
			}
		} else {
			throw new ToolArgumentException("data must be an XML string or a JSON object");
		}

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("template_path", template.toString());
		meta.put("output_path", out.toString());
		StringBuilder report = new StringBuilder();

		if (xml != null) {
			if (!hasCustomXml) {
				throw new ToolArgumentException("this template has no custom XML data part to bind"
						+ (mergeFields.isEmpty() ? "" : "; it has MERGEFIELDs " + mergeFields + " — pass data as a JSON object instead"));
			}
			int flags = Docx4J.FLAG_BIND_INSERT_XML | Docx4J.FLAG_BIND_BIND_XML;
			if (args.bool("remove_sdts", true)) {
				flags |= Docx4J.FLAG_BIND_REMOVE_SDT;
			}
			if (args.bool("remove_xml", true)) {
				flags |= Docx4J.FLAG_BIND_REMOVE_XML;
			}
			Docx4J.bind(pkg, xml, flags);
			meta.put("mode", hasXpaths ? "opendope" : "content_controls");
			report.append("Filled ").append(hasXpaths ? "OpenDoPE" : "content-control").append(" template from XML data.");
		} else {
			if (mergeFields.isEmpty()) {
				throw new ToolArgumentException("this template has no MERGEFIELDs"
						+ (hasCustomXml ? "; it takes XML data — see describe_template's skeleton_xml" : ""));
			}
			final Map<String, Object> supplied = json;
			Map<DataFieldName, String> values = new LinkedHashMap<>();
			List<String> unused = new ArrayList<>();
			for (Map.Entry<String, Object> e : supplied.entrySet()) {
				values.put(new DataFieldName(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
				if (mergeFields.stream().noneMatch(f -> f.equalsIgnoreCase(e.getKey()))) {
					unused.add(e.getKey());
				}
			}
			List<String> missing = mergeFields.stream()
					.filter(f -> supplied.keySet().stream().noneMatch(k -> k.equalsIgnoreCase(f)))
					.toList();
			try {
				MailMerger.performMerge(pkg, values, args.bool("headers_footers", true));
			} catch (RuntimeException e) {
				Map<String, String> formatted = new LinkedHashMap<>();
				mergeFieldMap.forEach((f, sw) -> {
					if (sw != null && (sw.contains("\\@") || sw.contains("\\#"))) {
						formatted.put(f, sw);
					}
				});
				throw new ToolArgumentException("merge failed: " + e.getMessage()
						+ (formatted.isEmpty() ? "" : ". These fields have format switches and need parseable values "
								+ "(dates as 20260901, 01/09/2026 day-first, or 1 September 2026; numbers as plain digits): "
								+ formatted));
			}
			meta.put("mode", "mail_merge");
			meta.put("merge_fields", mergeFields);
			meta.put("missing_fields", missing);
			meta.put("unused_keys", unused);
			report.append("Merged ").append(values.size() - unused.size()).append(" of ").append(mergeFields.size())
					.append(" MERGEFIELDs.");
			if (!missing.isEmpty()) {
				report.append(" Not supplied (left empty): ").append(missing).append('.');
			}
			if (!unused.isEmpty()) {
				report.append(" Keys not in template (ignored): ").append(unused).append('.');
			}
		}

		pkg.save(out.toFile());
		long bytes = ToolSupport.sizeOf(out);
		meta.put("bytes", bytes);
		report.append(" Wrote ").append(out).append(" (").append(bytes).append(" bytes).");
		return ToolSupport.text(report.toString(), meta);
	}
}
