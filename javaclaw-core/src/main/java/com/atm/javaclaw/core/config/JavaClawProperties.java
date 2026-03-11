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

        public String getAuthToken() { return authToken; }
        public void setAuthToken(String authToken) { this.authToken = authToken; }
        public List<String> getAllowlist() { return allowlist; }
        public void setAllowlist(List<String> allowlist) { this.allowlist = allowlist; }
        public boolean isDmPairingEnabled() { return dmPairingEnabled; }
        public void setDmPairingEnabled(boolean dmPairingEnabled) { this.dmPairingEnabled = dmPairingEnabled; }
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
