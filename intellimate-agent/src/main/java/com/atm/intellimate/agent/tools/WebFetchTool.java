package com.atm.intellimate.agent.tools;

import com.atm.intellimate.core.exception.ToolExecutionException;
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

    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(description = "访问指定 URL 并返回响应内容的文本")
    public String webFetch(
            @ToolParam(description = "要访问的 URL") String url,
            @ToolParam(description = "超时秒数（默认 15）", required = false) Integer timeoutSeconds
    ) {
        int timeout = (timeoutSeconds != null && timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .GET()
                    .header("User-Agent", "IntelliMate/1.0")
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
