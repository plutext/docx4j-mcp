package org.docx4j.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.docx4j.jaxb.Context;
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
 * <pre>java -jar docx4j-mcp.jar --root DIR [--root DIR ...]</pre>
 *
 * stdout carries the protocol; all logging goes to stderr (see logback.xml).
 */
public final class Docx4jMcpServer {

	private static final Logger log = LoggerFactory.getLogger(Docx4jMcpServer.class);

	public static final String NAME = "docx4j-mcp";
	public static final String VERSION = "0.1.0";

	public static void main(String[] args) throws Exception {
		PathPolicy paths;
		try {
			paths = new PathPolicy(parseRoots(args));
		} catch (IllegalArgumentException e) {
			System.err.println("docx4j-mcp: " + e.getMessage());
			System.err.println("usage: java -jar docx4j-mcp.jar --root DIR [--root DIR ...]");
			System.exit(2);
			return;
		}

		McpJsonMapper mapper = McpJsonDefaults.getMapper();
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
				.instructions("Tools for reading, converting and filling Word (.docx) documents with docx4j. "
						+ "File paths must be inside the server's allowed roots: " + paths.roots())
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.tools(ExtractTextTool.spec(paths, mapper))
				.build();

		log.info("{} {} up; roots={}", NAME, VERSION, paths.roots());

		// Warm-up (CR §4): pay for JAXB context init now, not on the first tool call.
		long t = System.currentTimeMillis();
		if (Context.jc == null) throw new IllegalStateException("JAXB context failed to initialise");
		log.info("JAXB context ready in {} ms", System.currentTimeMillis() - t);

		// The transport's stdin reader runs on its own (daemon) thread; keep main alive until the client goes away.
		stdinClosed.await();
		log.info("stdin closed; shutting down");
		server.closeGracefully();
		System.exit(0);
	}

	static List<Path> parseRoots(String[] args) {
		List<Path> roots = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if ("--root".equals(args[i])) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("--root needs a directory");
				}
				roots.add(Path.of(args[++i]));
			} else {
				throw new IllegalArgumentException("unknown argument: " + args[i]);
			}
		}
		return roots;
	}
}
