package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.agent.model.ChatModelFactory;
import com.atm.javaclaw.agent.model.ProviderConfig;
import com.atm.javaclaw.agent.model.ProviderType;
import com.atm.javaclaw.gateway.entity.ModelProviderEntity;
import com.atm.javaclaw.gateway.repository.ModelDefinitionRepository;
import com.atm.javaclaw.gateway.repository.ModelProviderRepository;
import com.atm.javaclaw.gateway.service.CryptoService;
import com.atm.javaclaw.gateway.service.ModelRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-providers")
public class ModelProviderController {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderController.class);

    private final ModelProviderRepository providerRepo;
    private final ModelDefinitionRepository definitionRepo;
    private final CryptoService cryptoService;
    private final ModelRegistryService registryService;
    private final ChatModelFactory chatModelFactory;

    public ModelProviderController(ModelProviderRepository providerRepo,
                                   ModelDefinitionRepository definitionRepo,
                                   CryptoService cryptoService,
                                   ModelRegistryService registryService,
                                   ChatModelFactory chatModelFactory) {
        this.providerRepo = providerRepo;
        this.definitionRepo = definitionRepo;
        this.cryptoService = cryptoService;
        this.registryService = registryService;
        this.chatModelFactory = chatModelFactory;
    }

    @GetMapping
    public Mono<List<ProviderDto>> list() {
        return providerRepo.findAllByOrderBySortOrder()
                .map(this::toDto)
                .collectList();
    }

    @PostMapping
    public Mono<ProviderDto> create(@RequestBody CreateProviderRequest body) {
        if (body.name == null || body.name.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
        }
        if (body.type == null || body.type.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required"));
        }
        if (body.apiKey == null || body.apiKey.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required"));
        }

        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setName(body.name.trim());
        entity.setType(body.type.trim());
        entity.setBaseUrl(body.baseUrl);
        entity.setApiKeyEncrypted(cryptoService.encrypt(body.apiKey.trim()));
        entity.setEnabled(1);
        entity.setSortOrder(body.sortOrder != null ? body.sortOrder : 0);

        return providerRepo.save(entity)
                .flatMap(saved -> registryService.reload().thenReturn(saved))
                .map(this::toDto);
    }

    @PutMapping("/{id}")
    public Mono<ProviderDto> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return providerRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found")))
                .flatMap(entity -> {
                    if (body.containsKey("name")) entity.setName((String) body.get("name"));
                    if (body.containsKey("type")) entity.setType((String) body.get("type"));
                    if (body.containsKey("baseUrl")) entity.setBaseUrl((String) body.get("baseUrl"));
                    if (body.containsKey("apiKey")) {
                        String raw = (String) body.get("apiKey");
                        if (raw != null && !raw.isBlank()) {
                            entity.setApiKeyEncrypted(cryptoService.encrypt(raw.trim()));
                        }
                    }
                    if (body.containsKey("enabled")) entity.setEnabled(((Number) body.get("enabled")).intValue());
                    if (body.containsKey("sortOrder")) entity.setSortOrder(((Number) body.get("sortOrder")).intValue());
                    return providerRepo.save(entity);
                })
                .flatMap(saved -> registryService.reload().thenReturn(saved))
                .map(this::toDto);
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return providerRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found")))
                .flatMap(entity -> definitionRepo.deleteByProviderId(id)
                        .then(providerRepo.delete(entity))
                        .then(registryService.reload())
                        .thenReturn(Map.<String, Object>of("success", true, "deletedName", entity.getName()))
                );
    }

    @PostMapping("/{id}/test")
    public Mono<Map<String, Object>> test(@PathVariable Long id) {
        return providerRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found")))
                .flatMap(entity -> {
                    String apiKey = cryptoService.decrypt(entity.getApiKeyEncrypted());
                    if (apiKey == null || apiKey.isBlank() || cryptoService.isPlaceholder(apiKey)) {
                        return Mono.just(Map.<String, Object>of("success", false, "error", "API Key 未配置"));
                    }

                    ProviderConfig config = new ProviderConfig(
                            entity.getId(), entity.getName(),
                            ProviderType.valueOf(entity.getType()),
                            entity.getBaseUrl(), apiKey);

                    ChatModel tempModel;
                    try {
                        tempModel = chatModelFactory.create(config);
                    } catch (Exception e) {
                        log.warn("Failed to create temp ChatModel for provider '{}': {}", entity.getName(), e.getMessage());
                        return Mono.just(Map.<String, Object>of("success", false, "error", "模型创建失败: " + e.getMessage()));
                    }

                    log.info("Testing connection for provider '{}' (type={})", entity.getName(), entity.getType());
                    return Mono.fromCallable(() -> tempModel.call(new Prompt("hi")))
                            .subscribeOn(Schedulers.boundedElastic())
                            .timeout(Duration.ofSeconds(15))
                            .map(resp -> {
                                log.info("Connection test succeeded for provider '{}'", entity.getName());
                                return Map.<String, Object>of("success", true, "message", "连接成功");
                            })
                            .onErrorResume(e -> {
                                String errMsg = extractErrorMessage(e);
                                log.warn("Connection test failed for provider '{}': {}", entity.getName(), errMsg);
                                return Mono.just(Map.<String, Object>of("success", false, "error", errMsg));
                            });
                });
    }

    private String extractErrorMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }
        if (e instanceof java.util.concurrent.TimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
            msg = "连接超时（15秒），请检查 API 地址是否可达";
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    @GetMapping("/{id}/models")
    public Mono<List<ModelDefinitionController.ModelItemDto>> getModels(@PathVariable Long id) {
        return definitionRepo.findByProviderIdOrderBySortOrder(id)
                .map(e -> new ModelDefinitionController.ModelItemDto(e.getId(), e.getModelId(), e.getDisplayName(), e.getDescription()))
                .collectList();
    }

    private ProviderDto toDto(ModelProviderEntity e) {
        String maskedKey = cryptoService.mask(cryptoService.decrypt(e.getApiKeyEncrypted()));
        return new ProviderDto(e.getId(), e.getName(), e.getType(), e.getBaseUrl(), maskedKey,
                e.getEnabled(), e.getSortOrder());
    }

    public record ProviderDto(Long id, String name, String type, String baseUrl, String apiKeyMasked,
                              Integer enabled, Integer sortOrder) {}

    public record CreateProviderRequest(String name, String type, String baseUrl, String apiKey, Integer sortOrder) {}
}
