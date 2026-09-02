# docx4j-mcp

A [Model Context Protocol](https://modelcontextprotocol.io/) server that exposes
[docx4j](https://www.docx4java.org/)'s engine to AI agents: read, convert and fill
Word (.docx) documents from Claude Desktop, Claude Code, or any MCP client.

**Engine: docx4j 17.0.4** (the server versions independently; the bundled docx4j
version is in the pom, the server's startup log, and its MCP instructions).

**Status: phase 3** — all tools work over stdio against released docx4j 17.0.4;
packaged as a runnable jar, an `.mcpb` bundle, an OCI image and a Claude Code
plugin.  The plan, tool surface and phasing are in [CR-mcp-server.md](CR-mcp-server.md).

## Build

Requires JDK 17+.  All docx4j dependencies (17.0.4) come from Maven Central.

```bash
mvn package            # -> target/docx4j-mcp.jar (shaded, runnable)
mvn test               # JUnit 5 tests against the tool handlers directly
```

## Run

```bash
java -jar target/docx4j-mcp.jar --root /path/to/documents [--root ...] [--max-inline-chars N]
```

Bare directory arguments are also accepted as roots (this is what the `.mcpb`
bundle's folder picker passes).

### Claude Desktop, one click

Install `docx4j-mcp.mcpb` from the GitHub release (Settings → Extensions), pick
the folders the server may touch, done.  Built locally with
`packaging/build-mcpb.sh`.  Requires Java 17+ on your PATH.

### Docker

```bash
docker run -i --rm -v /path/to/documents:/data ghcr.io/plutext/docx4j-mcp
```

(paths in tool calls are then container paths under `/data`;
`packaging/docker/Dockerfile` to build locally).

### Claude Code plugin

`packaging/claude-plugin/` bundles the server config and a skill for the
template/authoring workflows.  Set `DOCX4J_MCP_JAR` (path to the jar) and
optionally `DOCX4J_MCP_ROOTS`.

## Releasing

Tag `vX.Y.Z`: `.github/workflows/release.yml` builds jar + mcpb, attaches them
to the GitHub release, pushes `ghcr.io/plutext/docx4j-mcp`, and publishes
`server.json` (sha filled in) to the official MCP registry via GitHub OIDC.

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
| `html_to_docx` | HTML → docx via docx4j-ImportXHTML. Loose HTML accepted (normalised with jsoup); `h1`–`h6` map to heading styles; optional `styles_template_path`; `mode: altchunk` embeds the HTML for Word to convert on open. Remote images/stylesheets are never fetched. |
| `convert_to_html` | docx → standalone HTML (visitor exporter; `image_dir_path` for images). |
| `extract_text` | Plain text, one line per paragraph/table. |

**Mathematics** is supported end to end (docx4j 17.0.4): TeX math in Markdown
(`$..$`) and MathML in HTML become real, editable Word equations (OMML), and
equations render in PDF (via jeuclid/FOP, no LaTeX toolchain) and HTML (native
MathML, no JavaScript) output.  Known limitation: a very long single display
equation is one atomic graphic in PDF and does not line-wrap.

Writers take `output_path` and refuse to overwrite unless `overwrite: true`.

## Example (Claude Code)

> Use describe_template on contracts/nda-template.docx, then fill it for Acme Pty Ltd and
> write contracts/nda-acme.docx, then convert that to PDF.

The agent gets the skeleton XML (or MERGEFIELD names), fills it, and calls
`fill_template` and `convert_to_pdf`; the results tell it what was written and which
fonts were substituted.

## Licence

Apache License 2.0.
