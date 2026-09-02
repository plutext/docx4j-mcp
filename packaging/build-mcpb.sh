#!/bin/bash
# Build target/docx4j-mcp.mcpb from the shaded jar + manifest. Run after: mvn package
set -euo pipefail
cd "$(dirname "$0")/.."
test -f target/docx4j-mcp.jar || { echo "run mvn package first" >&2; exit 1; }
STAGE=target/mcpb-stage
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp packaging/mcpb/manifest.json "$STAGE/"
cp target/docx4j-mcp.jar "$STAGE/"
(cd "$STAGE" && zip -q -X -r ../docx4j-mcp.mcpb manifest.json docx4j-mcp.jar)
sha256sum target/docx4j-mcp.mcpb
