package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.memory.perception.ImportanceAssessor;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record AgentLoopContext(
    ChatModel chatModel,
    List<Message> history,
    ChatOptions options,
    int maxTurns,
    Duration timeout,
    String agentName,
    Long sessionId,
    String skillsBasePath,
    ToolCallLoopDetector loopDetector,
    WorkingMemory workingMemory,
    ImportanceAssessor importanceAssessor,
    TokenEstimator tokenEstimator,
    ToolResultCache cache,
    ToolApprovalGate approvalGate,
    Duration toolTimeout,
    int maxParallel,
    Set<String> nonRetryableTools,
    Long activePlanMessageId,
    AgentRunRequest originalRequest,
    Map<String, ToolCallback> toolCallbackMap
) {}
