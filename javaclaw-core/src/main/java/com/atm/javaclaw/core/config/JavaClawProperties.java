package com.atm.javaclaw.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level configuration bound to the "javaclaw" prefix in application.yml.
 */
@ConfigurationProperties(prefix = "javaclaw")
public class JavaClawProperties {

    private Server server = new Server();
    private Security security = new Security();
    private Agent agent = new Agent();
    private Map<String, ChannelConfig> channels = new HashMap<>();

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public Map<String, ChannelConfig> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelConfig> channels) {
        this.channels = channels;
    }

    public static class Server {
        private int port = 3007;
        private String host = "0.0.0.0";

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
    }

    public static class Security {
        private String authToken;
        private List<String> allowlist = new ArrayList<>();
        private boolean dmPairingEnabled = false;
        private String cryptoKey;

        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
        public List<String> getAllowlist() { return allowlist; }
        public void setAllowlist(List<String> allowlist) { this.allowlist = allowlist; }
        public boolean isDmPairingEnabled() { return dmPairingEnabled; }
        public void setDmPairingEnabled(boolean dmPairingEnabled) { this.dmPairingEnabled = dmPairingEnabled; }
        public String getCryptoKey() { return cryptoKey; }
        public void setCryptoKey(String cryptoKey) { this.cryptoKey = cryptoKey; }
    }

    public static class Agent {
        private String name = "javaclaw";
        private String model = "qwen-max";
        private int maxTurns = 128;
        private int timeoutSeconds = 300;
        private String systemPrompt;
        private String soulMd;
        private String userMd;
        private String agentsMd;

        private int maxToolResultChars = 16_000;
        private int maxContextTokens = 128_000;
        private int toolExecutionTimeoutSeconds = 60;
        private int maxParallelToolCalls = 8;
        private int historyLimit = 50;
        private int loopDetectorWindowSize = 8;
        private int loopDetectorWarnThreshold = 3;
        private int loopDetectorTerminateThreshold = 5;
        private List<String> loopDetectorExcludedTools = new ArrayList<>();
        private List<String> nonRetryableTools = new ArrayList<>();
        private List<String> approvalRequiredTools = new ArrayList<>();
        private boolean enableParallelToolCalls = true;
        private int condenserKeepRecent = 20;
        private int condenserSummaryLength = 200;
        private int condenserMinTurnsBetween = 5;
        private int planMaxSteps = 20;
        private int planStepTimeoutSeconds = 120;
        private int planApprovalTimeoutSeconds = 600;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTurns() { return maxTurns; }
        public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getSoulMd() { return soulMd; }
        public void setSoulMd(String soulMd) { this.soulMd = soulMd; }
        public String getUserMd() { return userMd; }
        public void setUserMd(String userMd) { this.userMd = userMd; }
        public String getAgentsMd() { return agentsMd; }
        public void setAgentsMd(String agentsMd) { this.agentsMd = agentsMd; }
        public int getMaxToolResultChars() { return maxToolResultChars; }
        public void setMaxToolResultChars(int maxToolResultChars) { this.maxToolResultChars = maxToolResultChars; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int maxContextTokens) { this.maxContextTokens = maxContextTokens; }
        public int getToolExecutionTimeoutSeconds() { return toolExecutionTimeoutSeconds; }
        public void setToolExecutionTimeoutSeconds(int toolExecutionTimeoutSeconds) { this.toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds; }
        public int getMaxParallelToolCalls() { return maxParallelToolCalls; }
        public void setMaxParallelToolCalls(int maxParallelToolCalls) { this.maxParallelToolCalls = maxParallelToolCalls; }
        public int getHistoryLimit() { return historyLimit; }
        public void setHistoryLimit(int historyLimit) { this.historyLimit = historyLimit; }
        public int getLoopDetectorWindowSize() { return loopDetectorWindowSize; }
        public void setLoopDetectorWindowSize(int loopDetectorWindowSize) { this.loopDetectorWindowSize = loopDetectorWindowSize; }
        public int getLoopDetectorWarnThreshold() { return loopDetectorWarnThreshold; }
        public void setLoopDetectorWarnThreshold(int loopDetectorWarnThreshold) { this.loopDetectorWarnThreshold = loopDetectorWarnThreshold; }
        public int getLoopDetectorTerminateThreshold() { return loopDetectorTerminateThreshold; }
        public void setLoopDetectorTerminateThreshold(int loopDetectorTerminateThreshold) { this.loopDetectorTerminateThreshold = loopDetectorTerminateThreshold; }
        public List<String> getLoopDetectorExcludedTools() { return loopDetectorExcludedTools; }
        public void setLoopDetectorExcludedTools(List<String> loopDetectorExcludedTools) { this.loopDetectorExcludedTools = loopDetectorExcludedTools; }
        public List<String> getNonRetryableTools() { return nonRetryableTools; }
        public void setNonRetryableTools(List<String> nonRetryableTools) { this.nonRetryableTools = nonRetryableTools; }
        public List<String> getApprovalRequiredTools() { return approvalRequiredTools; }
        public void setApprovalRequiredTools(List<String> approvalRequiredTools) { this.approvalRequiredTools = approvalRequiredTools; }
        public boolean isEnableParallelToolCalls() { return enableParallelToolCalls; }
        public void setEnableParallelToolCalls(boolean enableParallelToolCalls) { this.enableParallelToolCalls = enableParallelToolCalls; }
        public int getCondenserKeepRecent() { return condenserKeepRecent; }
        public void setCondenserKeepRecent(int condenserKeepRecent) { this.condenserKeepRecent = condenserKeepRecent; }
        public int getCondenserSummaryLength() { return condenserSummaryLength; }
        public void setCondenserSummaryLength(int condenserSummaryLength) { this.condenserSummaryLength = condenserSummaryLength; }
        public int getCondenserMinTurnsBetween() { return condenserMinTurnsBetween; }
        public void setCondenserMinTurnsBetween(int condenserMinTurnsBetween) { this.condenserMinTurnsBetween = condenserMinTurnsBetween; }
        public int getPlanMaxSteps() { return planMaxSteps; }
        public void setPlanMaxSteps(int planMaxSteps) { this.planMaxSteps = planMaxSteps; }
        public int getPlanStepTimeoutSeconds() { return planStepTimeoutSeconds; }
        public void setPlanStepTimeoutSeconds(int planStepTimeoutSeconds) { this.planStepTimeoutSeconds = planStepTimeoutSeconds; }
        public int getPlanApprovalTimeoutSeconds() { return planApprovalTimeoutSeconds; }
        public void setPlanApprovalTimeoutSeconds(int planApprovalTimeoutSeconds) { this.planApprovalTimeoutSeconds = planApprovalTimeoutSeconds; }
    }

    public static class ChannelConfig {
        private boolean enabled = false;
        private Map<String, Object> settings = new HashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, Object> getSettings() { return settings; }
        public void setSettings(Map<String, Object> settings) { this.settings = settings; }
    }
}
