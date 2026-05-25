package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.skills.SkillGroupContext;
import com.atm.intellimate.agent.skills.SkillUsageRecorder;
import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.agent.tools.AgentSessionContext;
import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.core.exception.ToolExecutionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ToolsEngine toolsEngine;
    private final AgentSessionContext agentSessionContext;
    private final SkillUsageRecorder skillUsageRecorder;
    private final AgentPromptBuilder agentPromptBuilder;
    private final ObjectMapper objectMapper;

    public ToolExecutionPipeline(ToolsEngine toolsEngine,
                                 AgentSessionContext agentSessionContext,
                                 @Autowired(required = false) SkillUsageRecorder skillUsageRecorder,
                                 AgentPromptBuilder agentPromptBuilder,
                                 ObjectMapper objectMapper) {
        this.toolsEngine = toolsEngine;
        this.agentSessionContext = agentSessionContext;
        this.skillUsageRecorder = skillUsageRecorder;
        this.agentPromptBuilder = agentPromptBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a single tool call with the full middleware chain:
     * loop detection -> approval gate -> cache check -> actual execution (with retry + timeout).
     * <p>
     * Returns a Mono of ToolExecutionResult. Approval events are emitted separately
     * via processToolCalls which handles the approval flow.
     */
    public Mono<ToolExecutionResult> executeSingleTool(
            AssistantMessage.ToolCall tc,
            String agentName,
            Long agentDbId,
            Long sessionId,
            String skillsBasePath,
            ToolCallLoopDetector loopDetector,
            ToolResultCache cache,
            Duration toolTimeout,
            Set<String> nonRetryableTools,
            Map<String, ToolCallback> toolCallbackMap) {

        ToolCallLoopDetector.LoopStatus loopStatus = loopDetector.check(tc.name(), tc.arguments());

        if (loopStatus == ToolCallLoopDetector.LoopStatus.TERMINATE) {
            log.warn("Tool call loop detected (TERMINATE): {}({})", tc.name(),
                    tc.arguments().length() > 100 ? tc.arguments().substring(0, 100) + "..." : tc.arguments());
            String terminateMsg = "检测到循环调用：你已多次使用相同参数调用 " + tc.name() + "，已拦截执行。请利用已有信息或尝试其他方法。";
            return Mono.just(new ToolExecutionResult(tc.id(), tc.name(), terminateMsg, false));
        }

        boolean appendWarning = (loopStatus == ToolCallLoopDetector.LoopStatus.WARN);

        return doExecuteTool(tc.id(), tc.name(), tc.arguments(), agentName, agentDbId, sessionId, skillsBasePath,
                cache, toolTimeout, nonRetryableTools, appendWarning, toolCallbackMap);
    }

    Mono<ToolExecutionResult> doExecuteTool(
            String toolCallId,
            String toolName,
            String arguments,
            String agentName,
            Long agentDbId,
            Long sessionId,
            String skillsBasePath,
            ToolResultCache cache,
            Duration toolTimeout,
            Set<String> nonRetryableTools,
            boolean appendWarning,
            Map<String, ToolCallback> toolCallbackMap) {

        String cached = cache.get(toolName, arguments);
        if (cached != null) {
            String result = cached + "\n[缓存结果]";
            if (appendWarning) {
                result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
            }
            return Mono.just(new ToolExecutionResult(toolCallId, toolName, result, true));
        }

        Mono<ToolExecutionResult> execution = Mono.fromCallable(() -> {
                    agentSessionContext.set(sessionId);
                    AgentContext.set(agentDbId, agentName);
                    Set<String> skillGroups = agentPromptBuilder.getSessionSkillGroups(sessionId);
                    if (skillGroups == null) {
                        SkillGroupContext.set(Set.of());
                    } else if (agentPromptBuilder.isUnrestrictedSkillGroups(skillGroups)) {
                        SkillGroupContext.set(null);
                    } else {
                        SkillGroupContext.set(skillGroups);
                    }
                    try {
                        ToolCallback callback = toolCallbackMap != null
                                ? toolCallbackMap.getOrDefault(toolName, toolsEngine.getCallbackByName(toolName))
                                : toolsEngine.getCallbackByName(toolName);
                        String result = callback.call(arguments);
                        log.debug("Tool {} executed, result length={}", toolName, result != null ? result.length() : 0);

                        recordSkillActivationIfApplicable(toolName, arguments, agentName, sessionId, skillsBasePath);

                        cache.put(toolName, arguments, result);
                        cache.invalidateForWrite(toolName, arguments);

                        if (appendWarning) {
                            result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
                        }
                        return new ToolExecutionResult(toolCallId, toolName, result != null ? result : "", true);
                    } finally {
                        SkillGroupContext.clear();
                        agentSessionContext.clear();
                        AgentContext.clear();
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .timeout(toolTimeout);

        if (!nonRetryableTools.contains(toolName)) {
            execution = execution.retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                    .filter(this::isRetryableError)
                    .doBeforeRetry(signal ->
                            log.info("Retrying tool {} (attempt {})", toolName, signal.totalRetries() + 1)));
        }

        return execution.onErrorResume(e -> {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            log.warn("Tool {} failed: {}", toolName, e.getMessage(), e);
            return Mono.just(new ToolExecutionResult(toolCallId, toolName, errorMsg, false));
        });
    }

    private boolean isRetryableError(Throwable e) {
        if (e instanceof SocketTimeoutException
                || e instanceof ConnectException
                || e instanceof TimeoutException) {
            return true;
        }
        if (e instanceof FileNotFoundException
                || e instanceof IllegalArgumentException
                || e instanceof SecurityException) {
            return false;
        }
        if (e instanceof ToolExecutionException) {
            return false;
        }
        return e.getMessage() != null && e.getMessage().contains("429");
    }

    private void recordSkillActivationIfApplicable(String toolName, String arguments,
                                                   String agentName, Long sessionId, String skillsBasePath) {
        if (skillUsageRecorder == null) return;

        try {
            if ("readFile".equals(toolName) && skillsBasePath != null) {
                String path = extractPathFromArgs(arguments);
                if (path != null && path.contains("/SKILL.md")) {
                    String skillName = extractSkillNameFromPath(path, skillsBasePath);
                    if (skillName != null) {
                        skillUsageRecorder.recordActivation(skillName, agentName, sessionId, "file_read");
                    }
                }
            } else if ("getSkillContent".equals(toolName)) {
                String skillName = extractSkillNameFromArgs(arguments);
                if (skillName != null) {
                    skillUsageRecorder.recordActivation(skillName, agentName, sessionId, "tool_call");
                }
            }
        } catch (Exception e) {
            log.debug("Failed to record skill activation: {}", e.getMessage());
        }
    }

    private String extractPathFromArgs(String arguments) {
        try {
            var node = objectMapper.readTree(arguments);
            return node.has("path") ? node.get("path").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSkillNameFromPath(String path, String skillsBasePath) {
        String normalized = path.replace("\\", "/");
        String basePath = skillsBasePath.replace("\\", "/");
        if (!basePath.endsWith("/")) basePath += "/";

        if (normalized.startsWith(basePath)) {
            String relative = normalized.substring(basePath.length());
            int slashIdx = relative.indexOf('/');
            return slashIdx > 0 ? relative.substring(0, slashIdx) : null;
        }
        return null;
    }

    private String extractSkillNameFromArgs(String arguments) {
        try {
            var node = objectMapper.readTree(arguments);
            return node.has("skillName") ? node.get("skillName").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
