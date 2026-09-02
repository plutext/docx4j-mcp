package org.docx4j.mcp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.docx4j.Docx4J;
import org.docx4j.convert.in.xhtml.XHTMLImporterImpl;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.AltChunkType;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * html_to_docx via docx4j-ImportXHTML.  Input is normalised to well-formed XHTML with jsoup
 * first (agents write loose HTML; ImportXHTML needs XML), and the CR §6 no-network rule is
 * enforced by stripping remote images and stylesheets before conversion.
 */
final class HtmlToDocxTool {

	static final String NAME = "html_to_docx";

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "html": {"type": "string", "description": "The HTML (loose HTML is fine; CSS in style attributes and <style> is honoured)"},
			    "input_path": {"type": "string", "description": "Alternatively, path to an .html file"},
			    "styles_template_path": {"type": "string", "description": "Optional .docx whose styles, numbering, page setup and headers/footers the output should use; its body content is replaced"},
			    "base_path": {"type": "string", "description": "Directory (inside an allowed root) against which relative image paths are resolved; defaults to input_path's directory. Remote (http/https) images are never fetched."},
			    "mode": {"type": "string", "enum": ["convert", "altchunk"], "description": "convert (default): translate to native Word markup now. altchunk: embed the HTML for Word to convert when the file is opened (highest fidelity in Word, but docx4j cannot render it to PDF)."},
			    "output_path": {"type": "string", "description": "Where to write the .docx"},
			    "overwrite": {"type": "boolean", "description": "Replace output_path if it exists (default false)"}
			  },
			  "required": ["output_path"]
			}
			""";

	private HtmlToDocxTool() {}

	static SyncToolSpecification spec(ServerConfig config) {
		return ToolSupport.spec(config, NAME, "Convert HTML to docx",
				"Creates a Word document from HTML using docx4j-ImportXHTML: headings, paragraphs, inline formatting, "
						+ "lists, tables, links and CSS become native Word markup, and <math> (MathML) becomes real, editable "
						+ "Word equations. Give a styles_template_path to inherit "
						+ "an organisation's styles (class names matching style names map to those styles). "
						+ "Remote images are not fetched; use data: URIs or local files under an allowed root.",
				SCHEMA, args -> run(config, args));
	}

	static CallToolResult run(ServerConfig config, ToolSupport.Args args) throws Exception {
		String source = args.oneOf("html", "input_path");
		Path inputPath = null;
		String html;
		if (source.equals("html")) {
			html = args.required("html");
		} else {
			inputPath = config.paths().resolveExisting("input_path", args.required("input_path"));
			html = Files.readString(inputPath, StandardCharsets.UTF_8);
		}
		Path out = config.paths().resolveOutput("output_path", args.required("output_path"), args.bool("overwrite", false));
		Path base = args.has("base_path")
				? config.paths().resolveDirectory("base_path", args.required("base_path"))
				: inputPath != null ? inputPath.getParent() : null;
		String mode = args.optional("mode") == null ? "convert" : args.optional("mode");
		if (!mode.equals("convert") && !mode.equals("altchunk")) {
			throw new ToolArgumentException("mode must be 'convert' or 'altchunk'");
		}

		List<String> warnings = new ArrayList<>();
		String xhtml = normalise(config, html, base, warnings);

		WordprocessingMLPackage pkg;
		if (args.has("styles_template_path")) {
			Path tpl = config.paths().resolveExisting("styles_template_path", args.required("styles_template_path"));
			pkg = Docx4J.load(tpl.toFile());
			pkg.getMainDocumentPart().getContent().clear();
		} else {
			pkg = WordprocessingMLPackage.createPackage();
		}
		MainDocumentPart mdp = pkg.getMainDocumentPart();

		int blocks;
		if (mode.equals("altchunk")) {
			mdp.addAltChunk(AltChunkType.Xhtml, xhtml.getBytes(StandardCharsets.UTF_8));
			blocks = 1;
		} else {
			XHTMLImporterImpl importer = new XHTMLImporterImpl(pkg);
			String baseUrl = base == null ? null : base.toUri().toString();
			List<Object> content = importer.convert(xhtml, baseUrl);
			mdp.getContent().addAll(content);
			blocks = content.size();
		}
		pkg.save(out.toFile());

		long bytes = ToolSupport.sizeOf(out);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("output_path", out.toString());
		meta.put("bytes", bytes);
		meta.put("mode", mode);
		meta.put("blocks", blocks);
		meta.put("warnings", warnings);
		StringBuilder sb = new StringBuilder("Wrote ").append(out).append(" (").append(bytes).append(" bytes, ")
				.append(mode.equals("altchunk") ? "HTML embedded as an altChunk for Word to convert on open"
						: blocks + " block-level elements")
				.append(").");
		if (!warnings.isEmpty()) {
			sb.append(" Warnings: ").append(String.join("; ", warnings)).append('.');
		}
		return ToolSupport.text(sb.toString(), meta);
	}

	/**
	 * Loose HTML → well-formed XHTML; remote images/stylesheets/scripts removed; relative
	 * image paths resolved under an allowed root (or removed).
	 */
	static String normalise(ServerConfig config, String html, Path base, List<String> warnings) {
		Document doc = Jsoup.parse(html);
		doc.outputSettings()
				.syntax(Document.OutputSettings.Syntax.xml)
				.escapeMode(Entities.EscapeMode.xhtml)
				.charset(StandardCharsets.UTF_8)
				.prettyPrint(false);

		for (Element script : doc.select("script")) {
			script.remove();
		}
		for (Element link : doc.select("link")) {
			if (isRemote(link.attr("href"))) {
				warnings.add("remote stylesheet not fetched: " + link.attr("href"));
			}
			link.remove();
		}
		for (Element img : doc.select("img")) {
			String src = img.attr("src").trim();
			if (src.startsWith("data:")) {
				continue;
			}
			if (isRemote(src)) {
				warnings.add("remote image not fetched: " + src);
				img.remove();
				continue;
			}
			String path = src.startsWith("file:") ? Path.of(java.net.URI.create(src)).toString() : src;
			Path resolved;
			try {
				Path candidate = Path.of(path);
				if (!candidate.isAbsolute()) {
					if (base == null) {
						warnings.add("relative image path with no base_path, dropped: " + src);
						img.remove();
						continue;
					}
					candidate = base.resolve(candidate);
				}
				resolved = config.paths().resolveExisting("img src", candidate.toString());
			} catch (ToolArgumentException e) {
				warnings.add("image dropped (" + e.getMessage() + ")");
				img.remove();
				continue;
			}
			img.attr("src", resolved.toUri().toString());
		}
		return doc.outerHtml();
	}

	private static boolean isRemote(String url) {
		String u = url.trim().toLowerCase();
		return u.startsWith("http:") || u.startsWith("https:") || u.startsWith("ftp:") || u.startsWith("//");
	}
}
