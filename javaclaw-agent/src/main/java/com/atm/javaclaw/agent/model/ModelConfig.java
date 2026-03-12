package com.atm.javaclaw.agent.model;

/**
 * Immutable snapshot of a model definition.
 *
 * @param definitionId  DB primary key of model_definition
 * @param providerId    FK to model_provider
 * @param modelId       model identifier sent to the API
 * @param displayName   human-readable name
 */
public record ModelConfig(
        Long definitionId,
        Long providerId,
        String modelId,
        String displayName
) {}
