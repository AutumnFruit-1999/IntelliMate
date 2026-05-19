package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.gateway.entity.BridgeNodeEntity;
import com.atm.intellimate.gateway.repository.BridgeNodeRepository;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeNodeRepository repository;
    private final BridgeNodeRegistry registry;

    public BridgeController(BridgeNodeRepository repository, BridgeNodeRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @GetMapping("/nodes")
    public Flux<Map<String, Object>> listNodes() {
        return repository.findAll().map(entity -> Map.<String, Object>of(
                "id", entity.getId(),
                "name", entity.getName(),
                "status", registry.isConnected(entity.getName()) ? "CONNECTED" : "DISCONNECTED",
                "registeredTools", entity.getRegisteredTools() != null ? entity.getRegisteredTools() : "[]",
                "lastConnectedAt", entity.getLastConnectedAt() != null ? entity.getLastConnectedAt().toString() : "",
                "lastHeartbeatAt", entity.getLastHeartbeatAt() != null ? entity.getLastHeartbeatAt().toString() : ""
        ));
    }

    @PostMapping("/nodes")
    public Mono<Map<String, Object>> createNode(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required"));
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenHash = sha256(token);

        BridgeNodeEntity entity = new BridgeNodeEntity();
        entity.setName(name);
        entity.setTokenHash(tokenHash);
        entity.setStatus("DISCONNECTED");

        return repository.save(entity)
                .map(saved -> Map.<String, Object>of(
                        "id", saved.getId(),
                        "name", saved.getName(),
                        "token", token,
                        "message", "请保存此 token，它只显示一次。使用方式：npx intellimate-local --server ws://host:3007/api/bridge/connect --token " + token
                ));
    }

    @DeleteMapping("/nodes/{name}")
    public Mono<Void> deleteNode(@PathVariable String name) {
        registry.unregister(name);
        return repository.findByName(name)
                .flatMap(repository::delete);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
