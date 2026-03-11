package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.agent.tools.ToolProfile;
import com.atm.javaclaw.agent.tools.ToolsEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ToolController {

    private final ToolsEngine toolsEngine;

    public ToolController(ToolsEngine toolsEngine) {
        this.toolsEngine = toolsEngine;
    }

    @GetMapping("/tools")
    public Mono<Map<String, Object>> getToolsMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("tools", toolsEngine.getToolMetadata());

        List<Map<String, Object>> profiles = Arrays.stream(ToolProfile.values()).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name().toLowerCase());
            m.put("tools", List.copyOf(p.getAllowedTools()));
            return m;
        }).toList();
        result.put("profiles", profiles);

        result.put("groups", toolsEngine.getAllGroups());

        return Mono.just(result);
    }
}
