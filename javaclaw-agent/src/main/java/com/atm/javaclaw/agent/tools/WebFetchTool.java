package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.core.exception.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class WebFetchTool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(description = "Fetch content from a URL and return the response body as text")
    public String webFetch(
            @ToolParam(description = "The URL to fetch") String url,
            @ToolParam(description = "Timeout in seconds (default 15)", required = false) Integer timeoutSeconds
    ) {
        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        log.info("Fetching URL: {} (timeout={}s)", url, timeout);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .GET()
                    .header("User-Agent", "JavaClaw/1.0")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return "HTTP " + response.statusCode() + "\n" + response.body();
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("web_fetch", "Failed to fetch URL: " + url, e);
        }
    }
}
