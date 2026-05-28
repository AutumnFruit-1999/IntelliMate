package com.atm.intellimate.core.exception;

public enum ErrorCode {
    AGENT_NOT_FOUND("AGENT_001", 404, "Agent not found"),
    AGENT_NAME_CONFLICT("AGENT_002", 409, "Agent name already exists"),
    SESSION_NOT_FOUND("SESSION_001", 404, "Session not found"),
    PLAN_NOT_FOUND("PLAN_001", 404, "Plan not found"),
    PLAN_INVALID_STATE("PLAN_002", 409, "Invalid plan state transition"),
    TOOL_NOT_FOUND("TOOL_001", 404, "Tool not found"),
    TOOL_EXECUTION_FAILED("TOOL_002", 500, "Tool execution failed"),
    MCP_SERVER_NOT_FOUND("MCP_001", 404, "MCP server not found"),
    MCP_CONNECTION_FAILED("MCP_002", 502, "MCP server connection failed"),
    MODEL_NOT_FOUND("MODEL_001", 404, "Model not found"),
    SKILL_NOT_FOUND("SKILL_001", 404, "Skill not found"),
    SKILL_GIT_CLONE_FAILED("SKILL_002", 500, "Git clone failed"),
    SKILL_NO_SKILL_MD("SKILL_003", 400, "SKILL.md not found in repository"),
    SKILL_GIT_SYNC_FAILED("SKILL_004", 500, "Git sync failed"),
    BRIDGE_NOT_FOUND("BRIDGE_001", 404, "Bridge node not found"),
    BRIDGE_TIMEOUT("BRIDGE_002", 504, "Bridge node timeout"),
    AUTH_INVALID_TOKEN("AUTH_001", 401, "Invalid authentication token"),
    VALIDATION_FAILED("VALIDATION_001", 400, "Validation failed"),
    INTERNAL_ERROR("INTERNAL_001", 500, "Internal server error");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
