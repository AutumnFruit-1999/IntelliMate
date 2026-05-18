package com.atm.intellimate.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-run LRU cache for read-only tool results.
 * Write operations trigger path-matching cache invalidation.
 */
public class ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCache.class);
    private static final int MAX_ENTRIES = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> CACHEABLE_TOOLS = Set.of(
            "readFile", "searchFiles", "listDirectory", "webFetch", "getSkillContent");

    private static final Set<String> WRITE_TOOLS = Set.of(
            "writeFile", "fileEdit", "exec");

    private final Map<String, CachedResult> cache = new LinkedHashMap<>(16, 0.75f, true);

    private record CachedResult(String result, long timestamp) {}

    /**
     * Returns a cached result or null if not cached / not cacheable.
     */
    public String get(String toolName, String arguments) {
        if (!CACHEABLE_TOOLS.contains(toolName)) {
            return null;
        }
        String key = toolName + "::" + arguments;
        CachedResult cached = cache.get(key);
        if (cached != null) {
            log.debug("Cache hit: {}({})", toolName, arguments.length() > 80 ? arguments.substring(0, 80) + "..." : arguments);
            return cached.result();
        }
        return null;
    }

    /**
     * Stores a tool result in the cache (only for cacheable tools).
     */
    public void put(String toolName, String arguments, String result) {
        if (!CACHEABLE_TOOLS.contains(toolName)) {
            return;
        }
        evictIfFull();
        String key = toolName + "::" + arguments;
        cache.put(key, new CachedResult(result, System.currentTimeMillis()));
    }

    /**
     * Invalidates cache entries affected by a write operation.
     * For writeFile/fileEdit, invalidates readFile entries with matching path.
     * For exec, clears the entire cache (exec can modify anything).
     */
    public void invalidateForWrite(String toolName, String arguments) {
        if (!WRITE_TOOLS.contains(toolName)) {
            return;
        }

        if ("exec".equals(toolName)) {
            if (!cache.isEmpty()) {
                log.debug("exec detected, clearing entire tool result cache ({} entries)", cache.size());
                cache.clear();
            }
            return;
        }

        String path = extractPath(arguments);
        if (path == null) {
            return;
        }

        Iterator<Map.Entry<String, CachedResult>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CachedResult> entry = it.next();
            if (entry.getKey().startsWith("readFile::") && entry.getKey().contains(path)) {
                log.debug("Invalidating cache entry for path: {}", path);
                it.remove();
            }
        }
    }

    private String extractPath(String arguments) {
        try {
            JsonNode node = MAPPER.readTree(arguments);
            return node.has("path") ? node.get("path").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void evictIfFull() {
        while (cache.size() >= MAX_ENTRIES) {
            Iterator<String> it = cache.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }
}
