package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.gateway.entity.AgentEntity;
import com.atm.javaclaw.gateway.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRepository agentRepository;
    private final JavaClawProperties properties;

    public AgentController(AgentRepository agentRepository, JavaClawProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    @GetMapping("/{name}")
    public Mono<Map<String, Object>> getAgent(@PathVariable String name) {
        return agentRepository.findByName(name)
                .map(this::entityToDto)
                .defaultIfEmpty(defaultDto(name));
    }

    @PutMapping("/{name}/context")
    public Mono<Map<String, Object>> updateContext(@PathVariable String name,
                                                    @RequestBody Map<String, String> body) {
        String soulMd = body.get("soulMd");
        String userMd = body.get("userMd");
        String agentsMd = body.get("agentsMd");

        log.info("Updating context for agent={}: soul={}chars, user={}chars, agents={}chars",
                name,
                soulMd != null ? soulMd.length() : 0,
                userMd != null ? userMd.length() : 0,
                agentsMd != null ? agentsMd.length() : 0);

        return agentRepository.updateContextByName(name, soulMd, userMd, agentsMd)
                .flatMap(rows -> {
                    if (rows > 0) {
                        return Mono.just(Map.<String, Object>of("success", true));
                    }
                    return createAndUpdate(name, soulMd, userMd, agentsMd);
                });
    }

    private Mono<Map<String, Object>> createAndUpdate(String name, String soulMd, String userMd, String agentsMd) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        AgentEntity entity = new AgentEntity();
        entity.setName(name);
        entity.setModel(defaults.getModel());
        entity.setMaxTurns(defaults.getMaxTurns());
        entity.setTimeoutSeconds(defaults.getTimeoutSeconds());
        entity.setSoulMd(soulMd);
        entity.setUserMd(userMd);
        entity.setAgentsMd(agentsMd);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        return agentRepository.save(entity)
                .doOnNext(saved -> log.info("Auto-created agent record: name={}, id={}", saved.getName(), saved.getId()))
                .thenReturn(Map.<String, Object>of("success", true));
    }

    private Map<String, Object> entityToDto(AgentEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", entity.getName());
        dto.put("model", entity.getModel());
        dto.put("soulMd", entity.getSoulMd());
        dto.put("userMd", entity.getUserMd());
        dto.put("agentsMd", entity.getAgentsMd());
        return dto;
    }

    private Map<String, Object> defaultDto(String name) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", name);
        dto.put("model", defaults.getModel());
        dto.put("soulMd", defaults.getSoulMd());
        dto.put("userMd", defaults.getUserMd());
        dto.put("agentsMd", defaults.getAgentsMd());
        return dto;
    }
}
