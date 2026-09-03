# docx4j-mcp over stdio in a container.
#   docker run -i -v /path/to/documents:/data ghcr.io/plutext/docx4j-mcp
# Build (from repo root, after mvn package):
#   docker build -f packaging/docker/Dockerfile -t docx4j-mcp .
FROM eclipse-temurin:17-jre
# MCP registry ownership annotation: must match server.json name
LABEL io.modelcontextprotocol.server.name="io.github.plutext/docx4j-mcp"
# fontconfig + a base font: FOP/jeuclid use AWT font machinery; a bare JRE image has none
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*
COPY target/docx4j-mcp.jar /app/docx4j-mcp.jar
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/docx4j-mcp.jar"]
CMD ["--root", "/data"]
