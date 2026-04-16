package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.core.exception.ToolExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String SERPAPI_BASE = "https://serpapi.com/search.json";

    @Value("${javaclaw.tools.serpapi-key:}")
    private String serpApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "使用搜索引擎在网络上搜索信息")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "最大结果数量（默认 5）", required = false) Integer maxResults
    ) {
        int limit = (maxResults != null && maxResults > 0) ? Math.min(maxResults, 10) : 5;
        log.info("Web search: query='{}', limit={}", query, limit);

        if (serpApiKey == null || serpApiKey.isBlank()) {
            return fallbackSearch(query, limit);
        }

        try {
            String url = SERPAPI_BASE
                    + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&num=" + limit
                    + "&api_key=" + serpApiKey
                    + "&engine=google";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("SerpAPI returned status {}", response.statusCode());
                return fallbackSearch(query, limit);
            }

            return formatSerpApiResults(response.body(), limit);
        } catch (Exception e) {
            log.warn("SerpAPI call failed, using fallback", e);
            return fallbackSearch(query, limit);
        }
    }

    private String formatSerpApiResults(String json, int limit) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode organicResults = root.path("organic_results");

            if (organicResults.isMissingNode() || !organicResults.isArray() || organicResults.isEmpty()) {
                return "No results found.";
            }

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (JsonNode result : organicResults) {
                if (count >= limit) break;
                count++;
                String title = result.path("title").asText("No title");
                String link = result.path("link").asText("");
                String snippet = result.path("snippet").asText("No description");

                sb.append(count).append(". **").append(title).append("**\n");
                sb.append("   URL: ").append(link).append("\n");
                sb.append("   ").append(snippet).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            throw new ToolExecutionException("webSearch", "Failed to parse search results", e);
        }
    }

    /**
     * Fallback when no API key is configured: returns an informational message
     * instead of throwing an error, so the agent can still proceed.
     */
    private String fallbackSearch(String query, int limit) {
        return "网络搜索未配置（未设置 SERPAPI_KEY）。无法搜索：" + query + "。请基于已有知识回答。";
    }
}
