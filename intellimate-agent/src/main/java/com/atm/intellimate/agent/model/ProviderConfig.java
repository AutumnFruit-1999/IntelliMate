package com.atm.intellimate.agent.model;

/**
 * Immutable snapshot of a model provider configuration.
 *
 * @param id           DB primary key
 * @param name         display name
 * @param type         provider type
 * @param baseUrl      API base URL (nullable, uses default)
 * @param apiKey       decrypted API key
 * @param thinkingMode DeepSeek thinking mode: "enabled", "disabled", or null (use API default)
 */
public record ProviderConfig(
        Long id,
        String name,
        ProviderType type,
        String baseUrl,
        String apiKey,
        String thinkingMode
) {}
