package com.atm.intellimate.core.prompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Loads prompt templates from classpath resource files with in-memory caching.
 * <p>
 * Resource files are expected to be UTF-8 encoded text (typically {@code .md}).
 * Once loaded, content is cached indefinitely since classpath resources are immutable at runtime.
 */
public final class PromptLoader {

    private static final ConcurrentMap<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {
    }

    /**
     * Loads a prompt template from the classpath.
     *
     * @param resourcePath classpath-relative path, e.g. {@code "prompts/plan-system.md"}
     * @return the full text content of the resource
     * @throws IllegalStateException if the resource cannot be found or read
     */
    public static String load(String resourcePath) {
        return CACHE.computeIfAbsent(resourcePath, PromptLoader::doLoad);
    }

    /**
     * Loads a prompt template and applies {@link String#format} substitutions.
     *
     * @param resourcePath classpath-relative path
     * @param args         format arguments (matched to {@code %s}, {@code %d}, etc.)
     * @return the formatted prompt text
     */
    public static String format(String resourcePath, Object... args) {
        return String.format(load(resourcePath), args);
    }

    /**
     * Clears the in-memory cache. Intended for testing only.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String doLoad(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PromptLoader.class.getClassLoader();
        }
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Prompt resource not found on classpath: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + resourcePath, e);
        }
    }
}
