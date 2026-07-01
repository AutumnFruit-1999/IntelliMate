package com.atm.intellimate.gateway.scheduler.jobs;

import com.atm.intellimate.gateway.scheduler.ScheduledJob;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpCallbackJob implements ScheduledJob {

    private final WebClient webClient;

    public HttpCallbackJob(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String getJobName() { return "http-callback"; }

    @Override
    public String getJobGroup() { return "custom"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(2); }

    @Override
    public boolean allowConcurrent() { return true; }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        Map<String, Object> params = context.params();
        String url = (String) params.get("url");
        String method = (String) params.getOrDefault("method", "GET");

        if (url == null || url.isBlank()) {
            return Mono.just(JobResult.fail("Missing 'url' parameter"));
        }

        WebClient.RequestHeadersSpec<?> request;
        if ("POST".equalsIgnoreCase(method)) {
            Object body = params.get("body");
            request = webClient.post().uri(url)
                    .bodyValue(body != null ? body : "");
        } else {
            request = webClient.get().uri(url);
        }

        return request.retrieve()
                .toBodilessEntity()
                .map(response -> {
                    int statusCode = response.getStatusCode().value();
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return JobResult.ok("HTTP " + statusCode, Map.of(
                                "url", url,
                                "method", method,
                                "statusCode", statusCode
                        ));
                    } else {
                        return JobResult.fail("HTTP " + statusCode, Map.of(
                                "url", url,
                                "method", method,
                                "statusCode", statusCode
                        ));
                    }
                })
                .onErrorResume(e -> Mono.just(JobResult.fail("Request failed: " + e.getMessage(), Map.of(
                        "url", url,
                        "error", e.getClass().getSimpleName()
                ))));
    }
}
