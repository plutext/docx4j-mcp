# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

docx4j-mcp is a stdio MCP server exposing docx4j's engine (template describe/fill, docx→PDF/HTML/Markdown,
Markdown/HTML→docx, text extraction) to AI agents.  The design, tool surface, phasing and per-phase findings
are in `CR-mcp-server.md`; read the relevant section before changing behaviour, and record findings there.
Phases 0-3 are done (v0.1.0 shipped 2026-09-03, live in the official MCP registry as
`io.github.plutext/docx4j-mcp`).  Phase 4 (extended tools, including the MergeDocx licence gate and the
`paginate` tool for docx4j CR-012) is gated on adoption signals; phase 5 (HTTP transport) on demand.

## Commands

JDK 17+ (this artifact only; docx4j itself is Java 11).  All dependencies come from Maven Central; the pom
does not build against the sibling `../docx4j` checkout.

```bash
mvn package                                   # tests + shaded jar -> target/docx4j-mcp.jar
mvn test                                      # JUnit 5 against the tool handlers (no MCP client)
mvn test -Dtest=PathPolicyTest                # one test class (or -Dtest=Class#method)
java -jar target/docx4j-mcp.jar --root DIR [--root DIR ...] [DIR ...] [--max-inline-chars N]
packaging/build-mcpb.sh                       # after mvn package -> target/docx4j-mcp.mcpb, prints sha256
docker build -f packaging/docker/Dockerfile -t docx4j-mcp .   # from repo root, after mvn package
```

The repo's `.mcp.json` runs `target/docx4j-mcp.jar --root .`, so Claude Code in this directory can drive the
locally built server.  End-to-end checks so far are scripted JSON-RPC over stdio (initialize → tools/list →
tools/call), not an in-process MCP client.

### Release

Push a tag `vX.Y.Z`; `.github/workflows/release.yml` sets the pom version from the tag
(`versions:set`), runs `mvn package`, seds the version into `packaging/mcpb/manifest.json`, builds the
mcpb and its SHA-256, creates the GitHub Release "vX.Y.Z (docx4j <docx4j.version>)" with the mcpb and jar,
pushes `ghcr.io/plutext/docx4j-mcp:<version>` and `:latest`, then rewrites `server.json` (version, mcpb URL +
`fileSha256`, OCI tag) and runs `mcp-publisher login github-oidc` + `mcp-publisher publish`.  The committed
`server.json` holds placeholders (zero sha) that CI fills in.

Registry validations that bit on v0.1.0 (CR §14):
- `server.json` `description` is capped at 100 characters.
- An OCI package is accepted only if the image carries
  `LABEL io.modelcontextprotocol.server.name="io.github.plutext/docx4j-mcp"`, matching the server name exactly
  (it is in the Dockerfile; keep them in sync if the name ever changes).

CI does not update `Docx4jMcpServer.VERSION` (the `serverInfo` version, hard-coded) or
`packaging/claude-plugin/.claude-plugin/plugin.json`; bump those by hand.

## Architecture

**Tools.**  One class per tool in `org.docx4j.mcp`, each with a `NAME`, a JSON Schema text block and a static
`spec(ServerConfig)`.  `spec` goes through `ToolSupport.spec(...)`, which builds the MCP `Tool` and a sync call
handler that wraps the arguments in `ToolSupport.Args` and maps exceptions: `ToolArgumentException` → an
`isError` result with the plain message (bad arguments, path refusals); anything else → logged, `isError`
with "<tool> failed: <root cause>".  Registration is the explicit `.tools(...)` list in
`Docx4jMcpServer.main`; a new tool must be added there, and to the `instructions` string, README table and
plugin skill if agents should route work to it (tool descriptions and instructions are the agent's only
discovery surface).  Tests call each tool's `run(config, args)` directly via `TestSupport` (roots =
`src/test/resources` plus temp dirs).

**Result conventions.**  Writers take `output_path` and refuse to overwrite unless `overwrite: true`.  Inline
text goes through `ToolSupport.deliverText`: capped at `--max-inline-chars` (default 200000, minimum 1000);
over the cap it is written to `output_path` if given, else truncated with a marker and `meta.truncated`.
Structured results (`describe_template`) use `ToolSupport.json` (JSON text plus `structuredContent`, no
`outputSchema`).  Operational details go in result `meta` (e.g. `font_substitutions`, `fonts_unmapped`,
`missing_fields`).  Keep result phrasing data-shaped: extracted document text is untrusted.

**Execution model** (CR §4).  Stateless per call: load, operate, save, close; no package handles between
calls.  Process-wide docx4j state is server-wide only: `docx4j.properties` and
`docx4j-ImportXHTML.properties` on the classpath (the latter turns on heading-style mapping); tools must not
mutate `Docx4jProperties` per request, and conversion settings go through settings objects/flags.
`warmUp()` runs JAXB/XML-parser init and font discovery (host fonts plus the bundled font jars) before the
transport starts, so the handshake is slower but the first call is not.  stdout is the protocol:
`logback.xml` sends all logging to stderr, and any `System.out` write corrupts the session.  The SDK does not
exit when the client closes stdin, so `main` wraps `System.in` to trip a latch on EOF, then
`closeGracefully()` and `System.exit(0)`.

**Sandboxing** (CR §6, `PathPolicy`).  Every path parameter, inputs and outputs alike, is resolved to an
absolute, normalised path with symlinks resolved on the nearest existing ancestor, and must `startsWith` one
of the roots (also real-path resolved).  There is no default root: `PathPolicy` refuses an empty list and the
server exits with usage (code 2).  Bare non-flag arguments are roots because the mcpb `directory` user_config
with `multiple: true` expands that way.  The server makes no network fetches: `html_to_docx` normalises HTML
with jsoup and removes `<script>`, `<link>` and remote `<img>` (reported as warnings), resolving local image
paths against `base_path` and the roots; markdown import does not fetch remote images.

**Engine version.**  Pinned by `docx4j.version` in the pom (currently 17.1.0), used for docx4j-core,
JAXB-ReferenceImpl, markdown, export-fo and the four export-fo font modules.  ImportXHTML has its own
`docx4j.importxhtml.version` (latest release, 17.0.4, checked binary-compatible by the test suite) and its
transitive JAXB-ReferenceImpl is excluded.  The running engine version is surfaced from
`org.docx4j.Version.getDocx4jVersion()` in the MCP `instructions` string and the startup log line; the
release workflow reads the pom property into the GitHub Release name, and README states it on the
"Engine:" line (update that by hand on a bump).  Engine bumps are recorded as a CR section (see §15).

**Optional and commercial components.**  ImportXHTML is a plain compile dependency in the shaded jar (the
reflection/optional idea in CR §2 was dropped in §12); `mode: altchunk` in `html_to_docx` embeds the HTML for
Word instead of converting.  MergeDocx (commercial, Plutext Enterprise) is not implemented yet: the phase 4
design is a `merge_documents` tool that is always listed and, without a licence (`MERGEDOCX_LICENSE`) or jar
present, returns a clear message and link rather than a degraded merge (CR §3, §5).  No detection code
exists today.

**Shaded jar.**  `maven-shade-plugin` produces `target/docx4j-mcp.jar` (~49 MB).
`ServicesResourceTransformer` is mandatory (the MCP JSON mapper and JAXB are both found via ServiceLoader;
without it startup fails with no mapper), the manifest sets `Multi-Release: true`, and signature files and
`module-info.class` are filtered out (docx4j-bundle lessons).

**Versioning.**  The server versions independently of docx4j; an engine upgrade plus surface growth is a
minor bump (CR §15: docx4j 17.1.0 + `update_toc` → v0.2.0).  The pom stays at a `-SNAPSHOT`; the tag is the
source of the release version.

**Packaging** (`packaging/`, CR §8 phase 3 and §14).  Every artefact wraps the same shaded jar; none rebuilds
it.  The mcpb is a zip of `packaging/mcpb/manifest.json` + the jar (`binary` server, `command: java`, roots as
`user_config` passed positionally; Java 17+ on PATH required).  The OCI image is eclipse-temurin:17-jre plus
fontconfig and DejaVu (FOP/jeuclid need AWT font machinery), entrypoint `java -jar`, default `--root /data`.
The Claude Code plugin is `plugin.json`, a `.mcp.json` using `DOCX4J_MCP_JAR` / `DOCX4J_MCP_ROOTS`, and the
`docx-documents` skill carrying when-to-use-which-tool guidance.  `server.json` lists the mcpb and OCI
packages (the registry has no jar/Maven package type).

## Portfolio task registry

This repository's change requests are indexed, with their dependencies on work in the other
docx4j repositories, in `../docx4j-portfolio/tasks.yaml` (ids `<repo>/<CR>[.<phase>]`; this
repository's key is `mcp`).

- When a CR's status changes (a phase lands; a CR is proposed, deferred or abandoned) or its
  **Depends on** changes, update the matching entry in `tasks.yaml` in the same session (`status`,
  `depends_on`; add an entry for a new CR or phase).
- Then run `python3 ../docx4j-portfolio/scripts/tasks.py check`. It reports `CHANGED` for each CR
  whose Status line was edited; once the registry entry agrees, run `tasks.py accept` (and
  `tasks.py graph` if dependencies changed).
- Before starting a CR or phase, check `python3 ../docx4j-portfolio/scripts/tasks.py blocked`: it
  may be waiting on work in another repository.
