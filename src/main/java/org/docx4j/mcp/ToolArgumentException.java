package org.docx4j.mcp;

/** A tool was called with bad arguments; reported to the agent as an isError result, not a protocol error. */
public class ToolArgumentException extends RuntimeException {
	public ToolArgumentException(String message) {
		super(message);
	}
}
