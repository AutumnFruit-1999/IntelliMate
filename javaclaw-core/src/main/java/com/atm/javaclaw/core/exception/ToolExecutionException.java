package com.atm.javaclaw.core.exception;

public class ToolExecutionException extends JavaClawException {

    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super("TOOL_ERROR", "[" + toolName + "] " + message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("TOOL_ERROR", "[" + toolName + "] " + message, cause);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
