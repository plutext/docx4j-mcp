# docx4j-mcp

A [Model Context Protocol](https://modelcontextprotocol.io/) server that exposes
[docx4j](https://www.docx4java.org/)'s engine to AI agents: read, convert and fill
Word (.docx) documents from Claude Desktop, Claude Code, or any MCP client.

**Status: phase 0 spike** — one toy tool (`extract_text`) over stdio.  The plan,
tool surface and phasing are in [CR-mcp-server.md](CR-mcp-server.md).

## Build

Requires JDK 17+ and a locally installed docx4j `17.0.4-SNAPSHOT`
(`mvn install -DskipTests` in the docx4j reactor) until 17.0.4 is released.

```bash
mvn package            # -> target/docx4j-mcp.jar (shaded, runnable)
mvn test               # JUnit 5 tests against the tool handlers directly
```

## Run

```bash
java -jar target/docx4j-mcp.jar --root /path/to/documents [--root ...]
```

Every file path an agent passes must resolve inside one of the `--root`
directories (symlinks are resolved first).  There is no default root; the server
refuses to start without one.  Logging goes to stderr; stdout is the protocol.

### Claude Code (`.mcp.json` in your project)

```json
{
  "mcpServers": {
    "docx4j": {
      "command": "java",
      "args": ["-jar", "/path/to/docx4j-mcp.jar", "--root", "/path/to/documents"]
    }
  }
}
```

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "docx4j": {
      "command": "java",
      "args": ["-jar", "/path/to/docx4j-mcp.jar", "--root", "/Users/you/Documents"]
    }
  }
}
```

## Tools

| Tool | Status |
|---|---|
| `extract_text` | phase 0 (toy) |
| `describe_template`, `fill_template`, `convert_to_pdf`, `markdown_to_docx`, `docx_to_markdown` | phase 1 |
| `html_to_docx`, `convert_to_html` | phase 2 |

## Licence

Apache License 2.0.
