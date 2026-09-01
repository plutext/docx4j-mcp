package org.docx4j.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

import org.docx4j.XmlUtils;
import org.docx4j.fonts.IdentityPlusMapper;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * docx4j MCP server, stdio transport.
 *
 * <pre>java -jar docx4j-mcp.jar --root DIR [--root DIR ...] [--max-inline-chars N]</pre>
 *
 * stdout carries the protocol; all logging goes to stderr (see logback.xml).
 */
public final class Docx4jMcpServer {

	private static final Logger log = LoggerFactory.getLogger(Docx4jMcpServer.class);

	public static final String NAME = "docx4j-mcp";
	public static final String VERSION = "0.1.0";

	public static void main(String[] argv) throws Exception {
		ServerConfig.Args args;
		PathPolicy paths;
		try {
			args = ServerConfig.Args.parse(argv);
			paths = new PathPolicy(args.roots());
		} catch (IllegalArgumentException e) {
			System.err.println("docx4j-mcp: " + e.getMessage());
			System.err.println("usage: java -jar docx4j-mcp.jar --root DIR [--root DIR ...] [--max-inline-chars N]");
			System.exit(2);
			return;
		}

		McpJsonMapper mapper = McpJsonDefaults.getMapper();
		ServerConfig config = new ServerConfig(paths, args.maxInlineChars(), mapper);

		// Pay for JAXB, XML parser and font discovery now, before the first tool call (CR §4).
		warmUp();

		// Spike finding: the SDK does not stop the JVM when the client closes stdin, so watch for EOF ourselves.
		CountDownLatch stdinClosed = new CountDownLatch(1);
		InputStream stdin = new FilterInputStream(System.in) {
			@Override public int read() throws IOException { return eof(super.read()); }
			@Override public int read(byte[] b, int off, int len) throws IOException { return eof(super.read(b, off, len)); }
			private int eof(int n) { if (n < 0) stdinClosed.countDown(); return n; }
		};
		StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, stdin, System.out);

		McpSyncServer server = McpServer.sync(transport)
				.serverInfo(NAME, VERSION)
				.instructions("docx4j tools for Word documents. Typical flows: describe_template then fill_template "
						+ "to produce a document from a template; markdown_to_docx to author a new document; "
						+ "docx_to_markdown to read one; html_to_docx / convert_to_html for HTML in and out; "
						+ "convert_to_pdf to render. All file paths must be inside "
						+ "the allowed roots: " + paths.roots() + ". Writing tools refuse to overwrite unless "
						+ "overwrite: true. Inline text results are capped at " + args.maxInlineChars()
						+ " chars; pass output_path for larger results.")
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.tools(
						DescribeTemplateTool.spec(config),
						FillTemplateTool.spec(config),
						ConvertToPdfTool.spec(config),
						MarkdownToDocxTool.spec(config),
						DocxToMarkdownTool.spec(config),
						HtmlToDocxTool.spec(config),
						ConvertToHtmlTool.spec(config),
						ExtractTextTool.spec(config))
				.build();

		log.info("{} {} up; roots={} maxInlineChars={}", NAME, VERSION, paths.roots(), args.maxInlineChars());

		// The transport's stdin reader runs on its own (daemon) thread; keep main alive until the client goes away.
		stdinClosed.await();
		log.info("stdin closed; shutting down");
		server.closeGracefully();
		System.exit(0);
	}

	/** One-off initialisation that would otherwise land on the first tool call. */
	static void warmUp() {
		long t = System.currentTimeMillis();
		try {
			WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
			pkg.getMainDocumentPart().addParagraphOfText("warm-up");
			XmlUtils.marshaltoString(pkg.getMainDocumentPart().getJaxbElement());
			log.info("JAXB ready in {} ms", System.currentTimeMillis() - t);
		} catch (Exception e) {
			log.warn("JAXB warm-up failed", e);
		}
		t = System.currentTimeMillis();
		try {
			new IdentityPlusMapper(); // triggers PhysicalFonts.discoverPhysicalFonts() (host fonts)
			int jarFonts = PhysicalFonts.discoverJarFonts(); // the bundled metric-compatible substitutes
			log.info("fonts ready in {} ms ({} physical fonts, {} from bundled jars)",
					System.currentTimeMillis() - t, PhysicalFonts.getPhysicalFonts().size(), jarFonts);
		} catch (Throwable e) {
			log.warn("font warm-up failed (PDF conversion may be slow or use fallback fonts)", e);
		}
	}
}
