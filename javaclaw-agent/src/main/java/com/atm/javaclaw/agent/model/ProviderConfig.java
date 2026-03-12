package com.atm.javaclaw.agent.model;

/**
 * Immutable snapshot of a model provider configuration.
 *
 * @param id       DB primary key
 * @param name     display name
 * @param type     provider type
 * @param baseUrl  API base URL (nullable, uses default)
 * @param apiKey   decrypted API key
 */
public record ProviderConfig(
        Long id,
        String name,
        ProviderType type,
        String baseUrl,
        String apiKey
) {}
