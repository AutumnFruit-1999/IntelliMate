package com.atm.intellimate.gateway.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

public final class BridgeProtocol {

    private BridgeProtocol() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Register.class, name = "register"),
            @JsonSubTypes.Type(value = Registered.class, name = "registered"),
            @JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
            @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result"),
            @JsonSubTypes.Type(value = ToolStream.class, name = "tool_stream"),
            @JsonSubTypes.Type(value = Ping.class, name = "ping"),
            @JsonSubTypes.Type(value = Pong.class, name = "pong"),
            @JsonSubTypes.Type(value = ErrorMsg.class, name = "error")
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public sealed interface Message permits Register, Registered, ToolCall, ToolResult,
            ToolStream, Ping, Pong, ErrorMsg {}

    public record Register(String name, List<String> tools,
                           List<McpToolGroup> mcpTools) implements Message {}

    public record McpToolGroup(String server, List<String> tools) {}

    public record Registered(String nodeId) implements Message {}

    public record ToolCall(String id, String tool, Map<String, Object> args) implements Message {}

    public record ToolResult(String id, boolean success, String result,
                             String error, Integer exitCode) implements Message {}

    public record ToolStream(String id, String chunk) implements Message {}

    public record Ping() implements Message {}

    public record Pong() implements Message {}

    public record ErrorMsg(String message) implements Message {}
}
