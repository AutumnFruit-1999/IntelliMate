package com.atm.javaclaw.gateway.pipeline;

import com.atm.javaclaw.core.prompt.PromptLoader;
import com.atm.javaclaw.core.protocol.GatewayFrame;
import com.atm.javaclaw.core.protocol.ResponseFrame;
import com.atm.javaclaw.gateway.entity.SessionEntity;
import com.atm.javaclaw.gateway.security.DmPairingService;
import com.atm.javaclaw.gateway.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Handles slash commands: /reset, /status, /model, /approve, /help
 */
@Component
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    private final SessionManager sessionManager;
    private final DmPairingService dmPairingService;

    public CommandHandler(SessionManager sessionManager, DmPairingService dmPairingService) {
        this.sessionManager = sessionManager;
        this.dmPairingService = dmPairingService;
    }

    public static boolean isCommand(String text) {
        return text != null && text.startsWith("/");
    }

    public Flux<GatewayFrame> handle(String text, SessionEntity session, String requestId) {
        String[] parts = text.trim().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        return switch (command) {
            case "/clear" -> handleClear(session, requestId);
            case "/reset" -> handleReset(session, requestId);
            case "/status" -> handleStatus(session, requestId);
            case "/model" -> handleModel(session, arg, requestId);
            case "/approve" -> handleApprove(arg, requestId);
            case "/plan" -> handlePlan(session, arg, requestId);
            case "/help" -> handleHelp(requestId);
            default -> Flux.just(ResponseFrame.failure(requestId,
                    "Unknown command: " + command + ". Type /help for available commands."));
        };
    }

    private Flux<GatewayFrame> handleClear(SessionEntity session, String requestId) {
        log.info("Clearing conversation for session: id={}", session.getId());
        return sessionManager.resetSession(session.getId())
                .thenMany(Flux.just(ResponseFrame.success(requestId,
                        Map.of("text", "对话已清除。", "command", "clear"))));
    }

    private Flux<GatewayFrame> handleReset(SessionEntity session, String requestId) {
        log.info("Resetting session: id={}", session.getId());
        return sessionManager.resetSession(session.getId())
                .thenMany(Flux.just(ResponseFrame.success(requestId,
                        Map.of("text", "会话已重置，上下文已清除。", "command", "reset"))));
    }

    private Flux<GatewayFrame> handleStatus(SessionEntity session, String requestId) {
        Map<String, Object> status = Map.of(
                "sessionId", session.getId(),
                "channelId", session.getChannelId(),
                "contextType", session.getContextType(),
                "contextId", session.getContextId(),
                "agentName", session.getAgentName() != null ? session.getAgentName() : "default",
                "lastActiveAt", session.getLastActiveAt() != null ? session.getLastActiveAt().toString() : "N/A"
        );
        return Flux.just(ResponseFrame.success(requestId, Map.of("text", formatStatus(status), "status", status)));
    }

    private Flux<GatewayFrame> handleModel(SessionEntity session, String modelName, String requestId) {
        if (modelName.isBlank()) {
            String current = session.getAgentName() != null ? session.getAgentName() : "default";
            return Flux.just(ResponseFrame.success(requestId,
                    Map.of("text", "Current model: " + current + ". Usage: /model <name>")));
        }
        session.setAgentName(modelName);
        return sessionManager.getOrCreate(
                        new com.atm.javaclaw.core.model.SessionKey(session.getChannelId(), session.getContextType(), session.getContextId()),
                        new com.atm.javaclaw.core.model.SessionMetadata(modelName, null, session.getChannelId(), session.getContextType(), session.getContextId()))
                .thenMany(Flux.just(ResponseFrame.success(requestId,
                        Map.of("text", "Model switched to: " + modelName))));
    }

    private Flux<GatewayFrame> handleApprove(String code, String requestId) {
        if (code.isBlank()) {
            return Flux.just(ResponseFrame.failure(requestId, "Usage: /approve <pairing-code>"));
        }
        return dmPairingService.approve(code)
                .map(msg -> (GatewayFrame) ResponseFrame.success(requestId, Map.of("text", msg)))
                .onErrorResume(e -> {
                    log.warn("Approve failed for code={}: {}", code, e.getMessage());
                    return Mono.just(ResponseFrame.failure(requestId, e.getMessage()));
                })
                .flux();
    }

    private Flux<GatewayFrame> handlePlan(SessionEntity session, String arg, String requestId) {
        if (arg.isBlank()) {
            return Flux.just(ResponseFrame.success(requestId,
                    Map.of("text", "用法：/plan <任务描述> — 强制以 Plan 模式处理任务",
                           "command", "plan", "forcePlan", true)));
        }
        return Flux.just(ResponseFrame.success(requestId,
                Map.of("text", arg, "command", "plan", "forcePlan", true)));
    }

    private Flux<GatewayFrame> handleHelp(String requestId) {
        String help = PromptLoader.load("prompts/command-help.md");
        return Flux.just(ResponseFrame.success(requestId, Map.of("text", help)));
    }

    private String formatStatus(Map<String, Object> status) {
        StringBuilder sb = new StringBuilder("Session Status:\n");
        status.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}
