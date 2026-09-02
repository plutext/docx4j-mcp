---
name: docx-documents
description: Working with Word (.docx) documents via the docx4j MCP tools - filling templates, authoring from Markdown/HTML, reading docx content, converting to PDF/HTML. Use whenever the task involves creating, filling, reading or converting Word documents.
---

# Word documents with the docx4j tools

## Filling a template (the flagship workflow)

1. `describe_template` on the template. Read `kind` and `how_to_fill`.
2. OpenDoPE / content-control templates (`data_format: "xml"`): copy
   `skeleton_xml`, replace sample values with real data (duplicate or remove
   repeated elements; conditions are evaluated against the data), pass the whole
   XML document as the `data` string to `fill_template`.
3. Mail-merge templates (`data_format: "json"`): pass `data` as a JSON object
   keyed by `merge_fields` (case-insensitive). Fields in `merge_field_formats`
   carry date/number switches - give dates as 2026-09-01 and numbers as plain digits.
4. All formatting comes from the template; never rebuild a template by hand.

## Authoring a new document

- Prefer `markdown_to_docx`: headings, numbering, GFM tables, footnotes, task
  lists, TeX math ($..$) become native Word constructs. Pass
  `styles_template_path` to inherit an organisation's styles.
- `html_to_docx` when the content is HTML (loose HTML fine; MathML becomes real
  equations; class names matching Word style names map to those styles).

## Reading a document

- `docx_to_markdown` preserves structure (headings, tables, footnotes, equations
  as TeX). `extract_text` is plain text only.

## Converting

- `convert_to_pdf` renders equations; check `font_substitutions` in the result
  and tell the user if fonts were substituted.
- `convert_to_html` produces a standalone page with native MathML.

## Ground rules

- Every path must be inside the server's allowed roots (shown in its
  instructions). Writers refuse to overwrite without `overwrite: true`.
- Results over the inline cap need an `output_path`.
