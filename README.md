# docx4j-mcp

A [Model Context Protocol](https://modelcontextprotocol.io/) server that exposes
[docx4j](https://www.docx4java.org/)'s engine to AI agents: read, convert and fill
Word (.docx) documents from Claude Desktop, Claude Code, or any MCP client.

**Status: phase 1** — the core tools work over stdio (see below).  The plan,
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
java -jar target/docx4j-mcp.jar --root /path/to/documents [--root ...] [--max-inline-chars N]
```

Every file path an agent passes must resolve inside one of the `--root`
directories (symlinks are resolved first).  There is no default root; the server
refuses to start without one.  Logging goes to stderr; stdout is the protocol.
Tools that return text inline cap it at `--max-inline-chars` (default 200000) and
otherwise write to `output_path` or truncate with a marker.

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

| Tool | What it does |
|---|---|
| `describe_template` | What data a template wants: skeleton XML + xpaths/conditions (OpenDoPE, bound content controls) or MERGEFIELD names and format switches (mail merge). Call before `fill_template`. |
| `fill_template` | Fill a template, preserving its formatting. `data` is an XML string (OpenDoPE / bound controls; repeats and conditions processed) or a JSON object (mail merge). |
| `convert_to_pdf` | docx → PDF via XSL-FO / Apache FOP; reports font substitutions. Bundles metric-compatible fonts (Carlito, Caladea, Liberation, Tinos…). |
| `markdown_to_docx` | Markdown → properly styled docx (headings, numbering, GFM tables, footnotes, task lists, TeX math). Optional `styles_template_path`. |
| `docx_to_markdown` | docx → Markdown (structure preserved). Options: `tracked_changes` accept/markup, `image_dir_path`. |
| `extract_text` | Plain text, one line per paragraph/table. |

Writers take `output_path` and refuse to overwrite unless `overwrite: true`.
Planned next (phase 2): `html_to_docx`, `convert_to_html`.

## Example (Claude Code)

> Use describe_template on contracts/nda-template.docx, then fill it for Acme Pty Ltd and
> write contracts/nda-acme.docx, then convert that to PDF.

The agent gets the skeleton XML (or MERGEFIELD names), fills it, and calls
`fill_template` and `convert_to_pdf`; the results tell it what was written and which
fonts were substituted.

## Licence

Apache License 2.0.
