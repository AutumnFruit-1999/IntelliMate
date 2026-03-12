package com.atm.javaclaw.agent.model;

import org.springframework.ai.chat.model.ChatModel;

/**
 * Result of resolving a model definition to a usable ChatModel instance + the model ID to pass per-request.
 *
 * @param chatModel  the ChatModel instance for the provider
 * @param modelId    the model identifier to set in ChatOptions
 */
public record ResolvedModel(
        ChatModel chatModel,
        String modelId
) {}
