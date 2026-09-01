# CR: docx4j MCP server (expose the engine to AI agents via Model Context Protocol)

Status: PHASE 2 DONE (proposed, reviewed, spiked, phases 1-2 built 2026-09-01); phase 3 (packaging/distribution) next
Scope: a NEW satellite artifact (`docx4j-mcp`) — no changes to
docx4j-core beyond what the tools need; lives in its own repo, `plutext/docx4j-mcp`
(decided 2026-09-01, §7).  Phase 0 findings are in §10.
Related: the "Why docx4j" website pages and Docx4j_from_Python.md (both answer
"I want docx4j's engine but don't write Java" — MCP is a third, stronger answer);
CR-fo-exporter-parity.md / CR-html-exporter-parity.md (the fast visitor exporters
are what a conversion tool should run on)

## 1. Background

Model Context Protocol (MCP) is the open protocol AI agents use to call external
tools: Claude Desktop, Claude Code, IDE assistants, and custom agent frameworks all
speak it, and MCP registries are becoming a discovery channel of their own.  A
server exposes *tools* (typed operations), optionally *resources* (readable data)
and *prompts*; clients connect over stdio (local process) or streamable HTTP.

The official Java SDK is `io.modelcontextprotocol.sdk` (verified 2026-09-01:
**2.0.1** on Maven Central — `mcp-bom`, `mcp-core`, `mcp` (= core + jackson2),
`mcp-json-jackson2` / `mcp-json-jackson3`, `mcp-test`; maintained with the Spring
AI team; Tier 2 in the MCP SDK tiering).  It requires **Java 17+**, provides sync
and async server APIs, and ships both stdio and streamable-HTTP transports in the
core module with no web framework dependency (`jakarta.servlet-api` is `provided`).
The server API shapes actually used are recorded in §10 from the phase 0 spike.

### Why this makes sense for docx4j

Agents are already producing and consuming Word documents, badly.  The popular
"Word MCP servers" are python-docx wrappers that build documents element by
element; Claude's own code-execution sandbox has python-docx preinstalled.  That
is "good enough" for casual generation — and visibly falls down exactly where
docx4j is strong:

1. **Template filling.** An agent writing raw OOXML (or driving python-docx call
   by call) to produce a contract/invoice/report is slow, token-hungry and
   error-prone.  docx4j's OpenDoPE binding turns that into one deterministic
   call — `Docx4J.bind(pkg, xmlData, FLAG_BIND_INSERT_XML | FLAG_BIND_BIND_XML |
   FLAG_BIND_REMOVE_SDT | FLAG_BIND_REMOVE_XML)` — preserving all authored
   formatting, with conditions and repeats.  Nothing in the MCP ecosystem offers
   this.  The 17.0.4 NonXSLT binding default makes it fast, too.
2. **Markdown↔docx** (docx4j-markdown, new in 17.0.4): Markdown is what agents
   actually write.  One facade call each way (`Docx4J.fromMarkdown` /
   `toMarkdown`), real styles/numbering/tables/footnotes, no optional-dependency
   dance — and the export doubles as *structured* text extraction (headings,
   tables, notes survive), so it is the reader tool as well as the writer tool.
   Promoted to phase 1 on review.
2a. **HTML→docx** (docx4j-ImportXHTML): the bridge for agents that produce HTML;
   phase 2 because ImportXHTML is a separate, lagging artifact (§2).
3. **docx→PDF / docx→HTML**: the 17.0.4 visitor exporters are at feature parity
   and roughly 10x faster than the XSLT pathway — right-sized for a tool an
   agent may call in a loop.

Secondary tools fall straight out of the module map: text/structure extraction
(agents reading a docx without vision tokens), tracked-changes handling,
anonymization (docx4j-docx-anon), comparison (docx4j-diffx), and merge/append —
the last being Plutext's commercial MergeDocx, which makes the server a natural
freemium funnel (§5).

### What this is NOT

- Not a dependency or feature of docx4j-core; a separate runnable artifact.
- Not a mirror of the docx4j API as tools.  Fine-grained tools ("add paragraph",
  "set bold") would be chattier and worse than what agents already do.  The value
  is a small number of coarse, deterministic, high-level operations.
- Not a replacement for using docx4j as a library; it is a reach/distribution
  surface (and a showcase).

## 2. Product shape

- **Artifact**: `docx4j-mcp`, a runnable shaded jar (`java -jar docx4j-mcp.jar`
  runs the stdio server).  The docx4j-bundle shading experience applies (JPMS
  descriptors excluded from the shade, JAXB runtime included — see the bundle
  notes from 17.0.x).
- **Java 17 baseline** for this artifact only (MCP SDK requirement); the library
  stays at 11.  Build-JDK is already ≥17, so a per-module
  `<maven.compiler.release>17</maven.compiler.release>` suffices if it lives in
  the reactor.
- **Transports**: stdio first (local agent, file paths, no auth story needed).
  Streamable HTTP is a later phase with a real upload/auth design (§6, phase 5).
- **Depends on**: docx4j-core, docx4j-JAXB-ReferenceImpl, docx4j-markdown,
  docx4j-export-fo (+ fonts), docx4j-ImportXHTML (optional, reflection or optional
  dependency — mirroring how the binding pathway treats it), docx4j-docx-anon /
  docx4j-diffx in the extended phase.
- **Release gating**: as a separate repo this consumes docx4j from Maven Central,
  so the ship-it milestone (phase 3) needs the **docx4j 17.0.4 release** (markdown
  module, NonXSLT binding default, the bundle shading fix).  `html_to_docx`
  additionally needs docx4j-ImportXHTML bumped from its current 17.0.3-SNAPSHOT to
  17.0.4.  Phases 0-2 build against the local 17.0.4-SNAPSHOT.
- **Client config snippets** (Claude Desktop `claude_desktop_config.json`,
  Claude Code `.mcp.json`) ship in the README and on a website page; listing in
  MCP registries once stable.

## 3. Tool surface

Contracts below are the design intent; JSON Schemas to be written in phase 1.
All file parameters — inputs *and* `output_path` — are local paths validated
against an allow-list of root directories given at server start (§6).  Every tool
that writes takes an `output_path` and refuses to overwrite unless `overwrite:
true`.  Every tool that returns text inline caps it (server option
`--max-inline-chars`, default 200k): past the cap the tool writes to `output_path`
if given, otherwise truncates and says so in the result metadata — an agent's
context window is finite and a 500-page docx is not.

### Core (phase 1)

| Tool | Input | Output | Engine |
|---|---|---|---|
| `describe_template` | `template_path` | The data the template wants, by template kind: **OpenDoPE** — a skeleton XML document derived from the XPaths part (types, repeats, conditions), ready for the agent to fill (§7); **plain content controls** — tags/titles of the sdts; **mail merge** — the MERGEFIELD names; plus a styles/parts summary | OpenDoPE parts (`org.opendope.xpaths` etc.), SdtPr traversal, `FieldsPreprocessor` / `MailMerger` field discovery |
| `fill_template` | `template_path`, `data` (OpenDoPE: XML string, the filled skeleton; mail merge: flat JSON object of field→value), `output_path`, optional `remove_sdts` (default true) | the filled docx | OpenDoPE: `Docx4J.bind(...)` with the FLAG_BIND_* set (OpenDoPEHandler preprocessing incl. conditions/repeats); MERGEFIELD: `org.docx4j.model.fields.merge.MailMerger` |
| `convert_to_pdf` | `input_path`, `output_path`, optional `font_mapper` hints | PDF | Docx4J.toPDF via export-fo (visitor exporter; XSLT fallback flag exposed as an option) |
| `extract_text` | `input_path` | plain text (paragraph per line); for structure use `docx_to_markdown` | TraversalUtil / TextUtils |
| `markdown_to_docx` | `markdown` (string) or `input_path`, optional `styles_template_path`, `output_path` | docx (real styles/numbering/tables/footnotes; no HTML detour) | docx4j-markdown `MarkdownImporter` (shipped 17.0.4; remote images NOT fetched — same posture as §6) |
| `docx_to_markdown` | `input_path`, optional `output_path`, `image_dir_path`, `tracked_changes` (accept/markup) | markdown (CommonMark+GFM) — this is the structured reader tool | docx4j-markdown `MarkdownExporter` (shipped 17.0.4) |

`describe_template` is the essential complement to `fill_template`: it is what
lets an agent discover *what data to supply* without a human reading the docx.
The pair is the product.  Mail-merge (MERGEFIELD) templates were added on review:
a large share of real-world templates are mail merges rather than content
controls, and `MailMerger` already exists in docx4j-core, so covering them is
cheap and widens the "template filling" claim considerably.

The markdown tools moved from phase 2 to phase 1 on review: they are cheaper than
`html_to_docx` (facade one-liners, no ImportXHTML), match what agents write, and
`docx_to_markdown` replaces the originally-planned `extract_text structure:true`
mode rather than duplicating it.

### Conversion & authoring (phase 2)

| Tool | Input | Output | Engine |
|---|---|---|---|
| `html_to_docx` | `html` (string) or `input_path`, optional `styles_template_path` (docx whose styles apply), `output_path` | docx | docx4j-ImportXHTML; altChunk fallback if ImportXHTML absent |
| `convert_to_html` | `input_path`, `output_path` or inline return (capped, §3 preamble) | HTML | Docx4J.toHTML (visitor exporter) |

### Extended (phase 4)

| Tool | Input | Output | Engine |
|---|---|---|---|
| `accept_tracked_changes` / `reject_tracked_changes` | `input_path`, `output_path` | docx | wml revision markup is fully modelled; operation code adapted from existing sample code |
| `anonymize` | `input_path`, `output_path` | docx with text scrambled, metadata stripped | docx4j-docx-anon |
| `compare` | `path_a`, `path_b` | a summary of differences (and optionally a marked-up docx) | docx4j-diffx |
| `merge_documents` | `input_paths[]`, `output_path` | docx | **MergeDocx (commercial)**: present in the tool list; without a licence key the tool returns a clear message + link (never a silent degraded merge). With `MERGEDOCX_LICENSE`/jar present, runs it. §5 |

### Resources and prompts (optional, phase 4+)

- Resources: expose an opened package's inventory (part names, styles in use,
  numbering summary) as readable resources for inspection/debugging.  Nice for a
  "docx inspector" story; strictly secondary to tools.
- Prompts: a `fill-template` prompt that walks a client through
  describe→gather-data→fill.  Cheap to add once tools exist.

## 4. Execution model

- **Stateless per call.**  Each tool call loads, operates, saves, closes.  No
  open-package session state: agents retry and parallelize, and docx4j load is
  fast relative to conversion.  (A session/handle model could come later if
  profiling demands it; it complicates crash/ordering semantics for no proven
  win.)
- **Concurrency**: the stdio transport is effectively serial per client; for
  HTTP later, note docx4j's process-wide state (`Docx4jProperties`
  programmatic settings, font caches) — per-request property mutation is
  forbidden in tool implementations; conversion settings go through
  FOSettings/HTMLSettings instances instead.
- **Fonts**: PDF quality depends on host fonts (the usual docx4j font-mapping
  story).  The server logs the substitutions it made and returns them in the
  tool result's metadata so the agent can tell the user ("Calibri rendered as
  Carlito").
- **Logging**: over stdio, MCP owns **stdout** for protocol frames.  All logging
  (logback, and docx4j's own "docx4j.properties not found" warning) goes to
  stderr, or to a file via `--log-file`; a stray `System.out.println` anywhere
  in the process corrupts the session.  The shaded jar ships a `logback.xml`
  that enforces this.
- **Warm-up**: a stdio server is spawned per client session, and the first
  tool call must not pay for JAXB context initialisation and font discovery
  (`PhysicalFonts.discoverPhysicalFonts`, seconds on a font-rich host).  Both
  run once at startup, right after the transport is up; the font mapper is
  built once and shared (consistent with the no-per-request-mutation rule
  above).  AppCDS for the shaded jar is a follow-up if measured startup
  matters.

## 5. Commercial angle (MergeDocx)

The server ships with the merge tool visible but licence-gated.  This is the
honest version of freemium: the agent (and its user) discovers that merge is a
solved problem one licence away, at exactly the moment they need it.  Requires:
a licence-key mechanism the server can check, wording for the unlicensed
response, and a decision on whether MergeDocx's jar is fetched separately
(likely, since it is closed source).  Plutext decision needed on pricing/keying
for this channel — flagging, not designing, here.

## 6. Security

- **Path allow-list**: server start takes `--root <dir>` (repeatable); every
  path parameter, `output_path` included, must resolve inside a root (symlinks
  resolved before the check).  No default root — refuse to start without one,
  so a copy-pasted config can't silently expose $HOME.
- **Result caps** (§3 preamble) are also a security measure: a hostile docx
  that expands to 100MB of text must not be able to flood the agent.
- **Untrusted input**: every docx is attacker-controlled (agents fetch files
  from anywhere).  Zip-bomb/entity-expansion posture: document what docx4j
  already guards (XML security properties in XmlUtils) and add size/entry
  limits at the server boundary; fuzz the loaders with the usual evil-zip
  corpus before the HTTP phase.
- **No network fetches** by the server itself (no template-by-URL) in v1; the
  agent's own fetch tools can do that, keeping this server's threat model
  file-only.
- **HTTP transport (phase 5) is where auth lives**: bearer token minimum, and
  uploads instead of paths.  Out of scope until the local server has earned it.

## 7. Risks / open questions

- **Repo placement**: DECIDED 2026-09-01 — separate GitHub repo
  (`plutext/docx4j-mcp`, the ImportXHTML pattern).  Keeps the Java 17 floor,
  the MCP SDK's release cadence, and registry versioning out of the library's.
  Cost: the release gating in §2.  This CR lives in that repo.
- **JSON→XML for `fill_template`**: OpenDoPE binds XPaths against the custom
  XML part, so XML is the native payload.  Agents prefer JSON.  Options: (a)
  accept both, converting JSON with a documented canonical mapping; (b) XML
  only, and let `describe_template` emit a skeleton XML the agent fills in;
  (c) `Docx4J.bind(pkg, Answers, flags)` — the existing questionnaire-style
  flat key/value payload, which is JSON-shaped already but only covers
  non-repeating templates.  **Recommendation: (b) for v1.**  The skeleton is
  self-teaching, gets repeats and conditions right by construction, and avoids
  inventing JSON↔XML rules for attributes/namespaces/ordering.  Revisit (a) only
  if phase 1 agent transcripts show agents fumbling the skeleton; (c) is not
  worth a second payload shape.  Mail-merge templates take flat JSON regardless
  (their data model *is* flat).
- **MCP SDK maturity**: Tier 2, 2.0.x — expect some API movement; pin the BOM
  and keep the server thin over it.
- **Prompt-injection surface**: tool *results* (extracted text, describe output)
  flow into the agent's context; a hostile docx contains hostile text.  That is
  inherent to any reader tool — document it, and keep results clearly data-
  shaped (no instructions in our own result phrasing).
- **Maintenance load**: a new user-facing product for a solo-maintained
  project.  Phases 1-3 are deliberately small; adoption signals (registry
  stats, issues) should gate phases 4-5.
- **Does anyone come?**  Cheap to find out: the phase 3 deliverable includes a
  website page and registry listing; if fill_template gets no traction in a
  couple of months, stop at phase 3.

## 8. Phases

0. **Spike** (S): stand up the SDK's stdio server with one toy tool
   (`extract_text`) and drive it from Claude Code.  Checklist: `pom.xml` (Java
   17, `mcp-bom` 2.0.1, `mcp-core` + `mcp-json-jackson2`, docx4j-core +
   JAXB-ReferenceImpl 17.0.4-SNAPSHOT, shade config lifted from docx4j-bundle);
   logging to stderr; `--root` allow-list; `.mcp.json` in the repo; a scripted
   JSON-RPC smoke test over stdio.  Proves: stdio framing, stderr logging,
   JAXB-in-shade, ServiceLoader-in-shade, Java-17-over-Java-11-libs.  Findings
   in §10.
1. **Core tools** (M): `describe_template`, `fill_template` (OpenDoPE +
   MERGEFIELD), `convert_to_pdf`, `extract_text`, `markdown_to_docx`,
   `docx_to_markdown`; result caps; startup warm-up; skeleton-XML contract
   (§7) checked against real agent transcripts.
   Tests: JUnit against the tool handlers directly (no MCP client needed for
   logic), plus one end-to-end stdio smoke test.
2. **Conversion & authoring** (S-M): `html_to_docx`, `convert_to_html`;
   ImportXHTML optional-dependency handling; needs ImportXHTML at 17.0.4.
3. **Packaging & distribution** (M): the ship-it milestone.  Discovery in 2026
   is directory- and client-driven (humans find servers in the official MCP
   Registry, the Claude Desktop Connectors menu, Claude Code plugin
   marketplaces and aggregator directories; agents only "discover" a server
   once a human has installed it, via `tools/list` and our `instructions`
   string), so packaging has to fit those channels.  Constraint that shapes
   everything: the official registry accepts only npm, pypi, cargo, nuget,
   oci and mcpb package types — there is no jar/Maven type — so a Java server
   ships as an **`.mcpb` bundle** and/or an **OCI image**.
   1. **`.mcpb` bundle** (MCP Bundle; formerly `.dxt`): zip of `manifest.json`
      + the shaded jar; the manifest declares the `java -jar` command and a
      user-configured `roots` setting (mapped to `--root`), so the allow-list
      survives one-click install.  Built by CI on tag; published as a GitHub
      Release asset (an allowlisted host for the registry's `mcpb` type, which
      also wants the SHA-256).  Serves Claude Desktop one-click install and
      the registry at once.  JRE remains a prerequisite; a jlink-bundled
      variant (~+40 MB) is a follow-up gated on adoption.
   2. **OCI image** (`ghcr.io/plutext/docx4j-mcp`): JRE + jar, stdio
      entrypoint, roots mounted as volumes.  Second registry package type,
      and the natural base for phase 5 (HTTP).
   3. **Claude Code plugin**: `.claude-plugin/plugin.json` + `.mcp.json` +
      a **skill** for the describe→fill and markdown/html authoring
      workflows.  The skill is the one place agent-side discovery genuinely
      happens today, so it carries the "when to use which tool" guidance
      rather than the tool descriptions alone.  Published via a
      `plutext/claude-plugins` marketplace repo (or the official marketplace
      if accepted).
   4. **Official MCP Registry** entry: `server.json` (name
      `io.github.plutext/docx4j-mcp`, GitHub-verified namespace; `org.docx4j`
      via DNS later if wanted), packages = mcpb + oci, published with
      `mcp-publisher` from the release workflow.  Aggregators (Glama,
      PulseMCP, mcp.so, Smithery) mirror the registry and crawl GitHub —
      claim those listings and tag the repo `mcp-server`.
   5. **Claude connector directory** submission (Team/Enterprise org, review
      against the annotation/safety requirements): worth attempting with the
      mcpb, but it favours remote servers — expect this to land with phase 5.
   6. **Docs**: README (done for the jar path; add mcpb/plugin/docker
      install), a website page in the "Why docx4j" family that targets the
      searches ("fill Word template MCP", "docx to PDF MCP"), client config
      snippets for Claude Desktop, Claude Code, Cursor/others.
   7. **Release mechanics**: version from the pom; CI builds jar + mcpb +
      image on tag, computes the SHA-256, publishes the registry entry.
      Gated on docx4j 17.0.4 and ImportXHTML 17.0.4 on Central (§2).
4. **Extended tools** (M): tracked changes, anonymize, compare, the MergeDocx
   licence gate; resources/prompts if warranted.
5. **Hosted/HTTP** (M-L, only on demonstrated demand): streamable HTTP
   transport, uploads, auth, hardening (§6).

## 9. Suggested sequencing and effort (rough)

| Phase | Effort | Value |
|-------|--------|-------|
| 0 Spike | S | De-risks everything else |
| 1 Core tools | M | The product: describe+fill is the differentiator |
| 2 Conversion/authoring | S-M | Broadens to the most-asked agent tasks |
| 3 Packaging/distribution | M | Without this, nothing above exists publicly; mcpb + plugin + registry are the actual discovery surfaces |
| 4 Extended tools | M | Depth + the MergeDocx funnel |
| 5 Hosted/HTTP | M-L | Only if adoption justifies the security surface |

If only phases 0-3 are ever done, that is a complete, shippable product:
template filling, PDF conversion, Markdown in/out and text extraction for any
MCP-capable agent, in a jar.

## 10. Phase 0 findings

(Filled in as the spike lands; keep this factual — it is what phase 1 builds on.)

Verified on 2026-09-01 against `mcp-core` 2.0.1 (javap, not recalled):

- **Entry point**: `McpServer.sync(McpServerTransportProvider)` returns a
  `SingleSessionSyncSpecification`; `.serverInfo(name, version)`,
  `.instructions(String)`, `.capabilities(McpSchema.ServerCapabilities.builder()
  .tools(true).build())`, `.tools(SyncToolSpecification...)`, `.build()` gives a
  `McpSyncServer`.  There are also `async(...)`, a streamable-HTTP flavour
  (`McpStreamableServerTransportProvider`) and a stateless flavour for phase 5.
- **Stdio transport**: `new StdioServerTransportProvider(McpJsonMapper)`
  (overloads taking explicit `InputStream`/`OutputStream`, useful for tests).
- **JSON mapper**: `McpJsonDefaults.getMapper()` resolves a
  `McpJsonMapperSupplier` via **ServiceLoader**; `mcp-json-jackson2` provides it
  (`jackson-databind` 2.21.1, plus `json-schema-validator` for
  `validateToolInputs`).  Consequence for shading: `ServicesResourceTransformer`
  is mandatory, otherwise the fat jar fails at startup with no mapper.
- **Tool definition**: `McpSchema.Tool.builder().name(..).description(..)
  .inputSchema(mapper, jsonSchemaString).build()`; handler is
  `BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest,
  McpSchema.CallToolResult>` with `request.arguments()` a `Map<String,Object>`;
  wrap as `McpServerFeatures.SyncToolSpecification.builder().tool(t)
  .callHandler(h).build()`.
- **Result**: `McpSchema.CallToolResult.builder().addTextContent(..)
  .isError(bool).meta(map).build()`; `structuredContent(Object)` + `Tool
  .outputSchema` exist for typed results (candidate for `describe_template`).
- **Runtime deps of mcp-core**: `slf4j-api` 2.0.16, `jackson-annotations` 2.21,
  `reactor-core` 3.7.0 (even for the sync API); `jakarta.servlet-api` provided.

Spike outcome (same day; code in this repo, `src/main/java/org/docx4j/mcp`):

- **All five proofs pass** against Claude-Code-style scripted JSON-RPC over
  stdio: `initialize` → `tools/list` → `tools/call extract_text` returns the
  document text; a path outside `--root` comes back as `isError: true` with a
  plain message; nothing but protocol frames reaches stdout.
- **Shading works** with the docx4j-bundle filters plus
  `ServicesResourceTransformer` (needed for both the MCP JSON mapper and JAXB)
  and a `Multi-Release: true` manifest entry.  Shaded jar: **26.9 MB**
  (docx4j-core + JAXB RI + MCP + reactor + jackson; no export-fo/FOP yet).
- **Java 17 module over Java 11 libs**: no issues; `--release 17` compile,
  docx4j 17.0.4-SNAPSHOT as ordinary classpath jars.
- **The SDK does not stop the JVM when the client closes stdin.**  The stdio
  reader runs on a daemon thread and the session just goes quiet; a naive
  `main` that blocks forever leaves a zombie JVM behind every closed client.
  Fix in the spike: wrap `System.in` in a `FilterInputStream` that trips a
  latch on EOF and pass it via the `(mapper, in, out)` constructor; `main`
  then calls `closeGracefully()` and exits.  Keep this in phase 1.
- **Startup**: JAXB context init ≈ 0.7 s after the transport is up; the first
  `extract_text` call still spends ≈ 3 s on first-use class loading and XML
  parser factory discovery (docx4j `XmlUtils` static init), so the phase 1
  warm-up should also touch `XmlUtils`/a tiny load, not just `Context.jc`.
  Font discovery (for `convert_to_pdf`) is not yet measured.
- **Logging**: logback `ConsoleAppender` with `<target>System.err</target>`
  is sufficient; docx4j's own warnings (unknown relTypes, DefaultPart etc.)
  correctly land on stderr.  A `docx4j.properties` on the classpath silences
  the not-found warning.
- **Tests**: JUnit 5 against `ExtractTextTool.extract`/`handler` and
  `PathPolicy` (`..` escape, symlink out of root, non-existent output path
  under root) — no MCP client needed, as planned in §8.
- Not done (deliberately): resource/prompt capabilities, `structuredContent`,
  the `mcp-test` module for an in-process client (worth a look for the phase 1
  end-to-end test instead of the shell-scripted smoke test).

## 11. Phase 1 findings

Built 2026-09-01 (same day as phase 0).  Six tools, 21 JUnit tests against the tool
logic, shaded jar 43.7 MB (now includes export-fo/FOP and the four bundled font
modules, +17 MB over phase 0).

- **Tool surface as shipped**: `describe_template`, `fill_template`,
  `convert_to_pdf`, `markdown_to_docx`, `docx_to_markdown`, `extract_text`.
  Every writer takes `output_path` + `overwrite`; every inline-text tool honours
  `--max-inline-chars` (default 200k) and falls back to `output_path` / a
  truncation marker (`meta.truncated`).
- **`describe_template` result shape** (JSON text + `structuredContent`):
  `kind` (opendope | content_controls | mail_merge | none), `data_format` (xml |
  json), `how_to_fill` (one paragraph the agent can act on), `skeleton_xml`,
  `xpaths[]`, `conditions[]` (rendered as readable expressions, xpaths inlined),
  `content_controls[]` (tag, title, role repeat/condition/bind/plain, binding,
  part), `merge_fields[]`, `merge_field_formats{}`, `document{}` (paragraphs,
  tables, styles/fonts in use).  The **skeleton is the template's own custom XML
  part** (the designer's sample data), pretty-printed — no need to synthesise one
  from the xpaths, and it is exactly what OpenDoPE authoring tools leave behind.
- **`fill_template` dispatch**: `data` is XML (string starting with `<`) →
  `Docx4J.bind` with INSERT|BIND (+REMOVE_SDT, +REMOVE_XML by default); `data` is
  a JSON object (or a JSON string) → `MailMerger.performMerge`.  Mismatches are
  explained ("this template has MERGEFIELDs [..] — pass data as a JSON object").
  Mail merge reports `missing_fields` and `unused_keys`; names match
  case-insensitively, mirroring `DataFieldName`.
- **MERGEFIELD discovery** reuses docx4j's own pieces
  (`FieldsPreprocessor.complexifyFields` → `ComplexFieldLocator` →
  `canonicalise` → `MailMerger.getDatafieldNameFromInstr`, reached via a
  package-private subclass) so the names agree with what the merge will match.
  Headers/footers included.
- **Format switches bite**: a `\@` date switch makes docx4j's
  `FormattingSwitchHelper` NPE ("date must not be null") on an unparseable
  value, and the default `DateFormatInferencer` does **not** accept ISO
  `yyyy-MM-dd` (that line is commented out in docx4j; day-first `dd/MM/yyyy`,
  `yyyyMMdd`, `1 September 2026` work).  Handled server-side: `describe_template`
  lists `merge_field_formats` and says which input shapes work; `fill_template`
  turns the NPE into a message naming the formatted fields.  **Fixed in docx4j
  the same day** (`5314fab9c`: ISO 8601 accepted; an unparseable value keeps
  the literal value and logs a warning instead of NPE); the server-side
  explanation stays as belt and braces.
- **PDF**: default `IdentityPlusMapper` plus `PhysicalFonts.discoverJarFonts()`
  at warm-up picks up the bundled croscore/crosextra/liberation/symbol fonts;
  the tool reports `font_substitutions` (eg "Times New Roman -> Tinos Regular")
  and `fonts_unmapped` (eg Aptos, Aptos Display — Office 2023's defaults have no
  metric-compatible free font, so expect this often).  Visitor exporter by
  default; `use_xslt: true` selects the XSLT pathway.
- **Markdown**: `styles_template_path` = load the template, clear the body,
  `MarkdownImporter.importToMainDocumentPart` — styles, numbering, sectPr and
  headers/footers survive.  Import issues (constructs kept literally) are
  returned in the result.  Export `tracked_changes` accept|markup and
  `image_dir_path` (relative image URIs when `output_path` is given).
- **Warm-up** (before the transport starts): create+marshal a tiny package
  (JAXB + XML parser statics) and font discovery; see the smoke-test timings
  below.  Runs before `initialize` is answered, so the client sees a slower
  handshake but never a slow first call.
- **Smoke test (shaded jar, scripted stdio)**: initialize → tools/list (6) →
  describe_template → fill_template (repeat + condition) → markdown_to_docx →
  convert_to_pdf → docx_to_markdown, all `isError: false`; warm-up 0.84 s JAXB +
  0.65 s fonts (1246 host fonts + 32 bundled) before the handshake.
- **docx4j bug found by the markdown→PDF flow (hand-off to the docx4j repo)**:
  a table built by `docx4j-markdown` is silently dropped by both PDF pathways
  (and by extension HTML).  Cause: the importer emits `<w:gridCol/>` without
  `w:w` (Word tolerates it), and
  `AbstractTableWriter.createColumns` (line 338) does
  `TblGridCol.getW().intValue()` → NPE, caught and logged as
  `AbstractWriterRegistry - Cannot convert org.docx4j.wml.Tbl`.  Fix belongs in
  docx4j: emit gridCol widths in `MarkdownToWmlVisitor` (equal split of the
  text width is what Word would do), and make `AbstractTableWriter` tolerate a
  missing width instead of dropping the whole table.  **Fixed in docx4j the
  same day** (`c6ce52cef`, both sides); no workaround needed here.
- **Not done / deferred**: `--log-file`; `structuredContent`'s companion
  `outputSchema` (declared none, so clients get untyped JSON); `mcp-test`-based
  in-process end-to-end test (still shell-scripted); resources/prompts.

## 12. Phase 2 findings

Built 2026-09-01.  Eight tools, 28 tests, shaded jar 47.9 MB (+4 MB for
ImportXHTML-core, openhtmltopdf, pdfbox, xerces, jsoup).

- **ImportXHTML dependency**: `docx4j-ImportXHTML-core` 17.0.3-SNAPSHOT from
  the local repo, installed with `-Dgpg.skip=true -Dversion.docx4j=17.0.4-SNAPSHOT`
  (its parent pom signs by default; its `version.docx4j` is 17.0.3-SNAPSHOT,
  which is not in `.m2`).  Plain compile-scope dependency in the fat jar — the
  "optional / reflection" idea from §2 buys nothing for a shaded artifact.
  Its docx4j-JAXB-ReferenceImpl dependency is excluded (we bring 17.0.4).
  Still needs the ImportXHTML 17.0.4 release before phase 3 can ship (§2).
- **Loose HTML**: `XHTMLImporterImpl.convert(String)` needs well-formed XML
  (`XMLResource.load` → SAXParseException on `<br>`, unclosed `<li>`/`<p>`,
  `<table border=1>`).  jsoup normalises to XHTML first
  (`Syntax.xml`, `EscapeMode.xhtml`), which also gives a DOM to enforce the
  §6 posture: `<script>` removed; `<link>` removed (remote href reported);
  `<img>` with http(s)/ftp/`//` src removed and reported; `data:` kept;
  relative/absolute file srcs resolved against `base_path` (default:
  `input_path`'s directory) and the roots, else dropped with a reason.  The
  importer's own user agent would otherwise fetch remote images.
- **Headings**: ImportXHTML maps `h1..h6` to the package's "heading N" styles
  only with `docx4j-ImportXHTML.Element.Heading.MapToStyle=true` (default
  false → bold paragraphs).  Shipped as `docx4j-ImportXHTML.properties` on the
  classpath (server-wide, like `docx4j.properties`).  docx4j's default styles
  part has the heading styles, so it works without a template too.
- **`mode: altchunk`**: `MainDocumentPart.addAltChunk(AltChunkType.Xhtml,
  bytes)` on the (normalised) HTML — Word converts on open.  Exposed because
  Word's HTML import is the fidelity ceiling; documented that docx4j cannot
  render it to PDF (so convert first if a PDF is wanted).
- **`convert_to_html`**: `HTMLSettings` + `Docx4J.toHTML(settings, os,
  FLAG_EXPORT_PREFER_NONXSL)`; `image_dir_path` with a relative
  `imageTargetUri` when `output_path` is given; inline result honours the cap.
  Output is XHTML 1.0 Transitional with the document's styles as CSS.
- **Smoke (shaded jar, stdio)**: tools/list (8) → html_to_docx (loose HTML with
  list, table, remote image → warning) → convert_to_pdf → convert_to_html →
  markdown_to_docx (table) → convert_to_pdf: all `isError: false`; the
  markdown table now renders in the PDF (docx4j `c6ce52cef`).
- **Phase 3 inputs** from phases 1-2: the shaded jar is ~48 MB and the
  handshake costs ~1.4 s of warm-up; both fine for a local stdio server but
  worth stating on the website page.  Registry listing needs a released
  docx4j 17.0.4 and ImportXHTML 17.0.4 on Central.

