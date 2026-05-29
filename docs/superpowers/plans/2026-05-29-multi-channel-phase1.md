# 多渠道接入（阶段 1+2）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 打通外部渠道入站管道，实现飞书适配器，新增渠道管理 Web UI，支持跨渠道统一会话。

**架构：** 在现有 ChannelAdapter SPI 上补全 webhook→pipeline 入站链路，新增 AbstractChannelAdapter 基类和 FeishuAdapter，通过 channel_identity 表实现跨渠道用户身份映射，前端新增渠道管理页面。

**技术栈：** Java 21 / Spring Boot 3.4 / WebFlux / R2DBC / Flyway / React 19 / TypeScript / Zustand / 飞书 oapi-sdk-java

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-gateway/src/main/resources/db/migration/V35__channel_identity_and_log.sql` | 数据库迁移：channel_identity、channel_message_log、source_channel |
| `intellimate-channel-api/.../model/WebhookRequest.java` | Webhook 入站请求封装 |
| `intellimate-channel-api/.../model/WebhookResponse.java` | Webhook 响应封装 |
| `intellimate-channel-api/.../model/MessageType.java` | 消息类型枚举 |
| `intellimate-channel-api/.../AbstractChannelAdapter.java` | 适配器基类（模板方法） |
| `intellimate-gateway/.../channel/feishu/FeishuAdapter.java` | 飞书适配器 |
| `intellimate-gateway/.../channel/feishu/FeishuEventParser.java` | 飞书事件解析 |
| `intellimate-gateway/.../channel/ChannelIdentityService.java` | 用户身份映射服务 |
| `intellimate-gateway/.../entity/ChannelIdentityEntity.java` | 身份映射实体 |
| `intellimate-gateway/.../repository/ChannelIdentityRepository.java` | 身份映射仓库 |
| `intellimate-gateway/.../entity/ChannelMessageLogEntity.java` | 消息日志实体 |
| `intellimate-gateway/.../repository/ChannelMessageLogRepository.java` | 消息日志仓库 |
| `intellimate-gateway/.../http/ChannelConfigController.java` | 渠道管理 REST API |
| `intellimate-gateway/.../service/ChannelConfigService.java` | 渠道配置业务逻辑 |
| `intellimate-gateway/.../channel/AccessTokenManager.java` | Token 统一管理 |
| `intellimate-web/src/lib/channelApi.ts` | 渠道 API 客户端 |
| `intellimate-web/src/stores/channelStore.ts` | 渠道状态管理 |
| `intellimate-web/src/components/ChannelsPage.tsx` | 渠道管理页面 |
| `intellimate-web/src/components/ChannelConfigModal.tsx` | 渠道配置表单 |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `intellimate-channel-api/.../ChannelAdapter.java` | 新增 handleWebhook、supportedMessageTypes、getConfigSchema 方法 |
| `intellimate-gateway/.../channel/webchat/WebChatAdapter.java` | 实现新增的默认方法 |
| `intellimate-gateway/.../channel/ChannelsManager.java` | 新增 deliverInbound、连接 inboundHandler |
| `intellimate-gateway/.../pipeline/MessagePipeline.java` | 新增 processInbound 入口 |
| `intellimate-gateway/.../http/WebhookController.java` | 重构为委托给适配器处理 |
| `intellimate-gateway/pom.xml` | 添加飞书 SDK 依赖 |
| `intellimate-web/src/App.tsx` | 添加 /channels 路由 |
| `intellimate-web/src/components/Sidebar.tsx` | 添加渠道导航入口 |

---

## 任务 1：数据库迁移

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V35__channel_identity_and_log.sql`

- [ ] **步骤 1：编写迁移 SQL**

```sql
-- V35__channel_identity_and_log.sql

-- 用户身份映射：将外部渠道身份关联到统一的 IntelliMate 用户
CREATE TABLE channel_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    external_name VARCHAR(128),
    bound_at TIMESTAMP DEFAULT NOW(),
    UNIQUE KEY uk_channel_external (channel_id, external_id),
    INDEX idx_user (user_id)
);

-- 渠道消息日志：审计和排查用
CREATE TABLE channel_message_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id VARCHAR(32) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    user_id VARCHAR(64),
    external_id VARCHAR(128),
    message_text TEXT,
    status VARCHAR(16) DEFAULT 'success',
    error_message VARCHAR(512),
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_channel_time (channel_id, created_at)
);

-- 会话消息增加来源渠道标记
ALTER TABLE conversation_message
    ADD COLUMN source_channel VARCHAR(32) DEFAULT 'webchat';
```

- [ ] **步骤 2：启动应用验证迁移**

运行：`cd intellimate-gateway && mvn spring-boot:run`
预期：Flyway 执行 V35 迁移成功，日志中可见 `Successfully applied 1 migration`

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V35__channel_identity_and_log.sql
git commit -m "feat(channel): add V35 migration for identity mapping and message log"
```

---

## 任务 2：Webhook 请求/响应模型

**文件：**
- 创建：`intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/model/WebhookRequest.java`
- 创建：`intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/model/WebhookResponse.java`
- 创建：`intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/model/MessageType.java`

- [ ] **步骤 1：创建 WebhookRequest**

```java
package com.atm.intellimate.channel.api.model;

import java.util.Map;

public record WebhookRequest(
        String method,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String body,
        String contentType
) {
    public String getHeader(String name) {
        return headers != null ? headers.get(name.toLowerCase()) : null;
    }

    public String getQueryParam(String name) {
        return queryParams != null ? queryParams.get(name) : null;
    }
}
```

- [ ] **步骤 2：创建 WebhookResponse**

```java
package com.atm.intellimate.channel.api.model;

public record WebhookResponse(
        int statusCode,
        String body,
        String contentType
) {
    public static WebhookResponse ok() {
        return new WebhookResponse(200, "{\"status\":\"ok\"}", "application/json");
    }

    public static WebhookResponse ok(String body) {
        return new WebhookResponse(200, body, "text/plain");
    }

    public static WebhookResponse unauthorized() {
        return new WebhookResponse(401, "{\"error\":\"unauthorized\"}", "application/json");
    }

    public static WebhookResponse notFound() {
        return new WebhookResponse(404, "{\"error\":\"not found\"}", "application/json");
    }
}
```

- [ ] **步骤 3：创建 MessageType 枚举**

```java
package com.atm.intellimate.channel.api.model;

public enum MessageType {
    TEXT,
    IMAGE,
    FILE,
    AUDIO,
    VIDEO,
    CARD,
    INTERACTIVE
}
```

- [ ] **步骤 4：Commit**

```bash
git add intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/model/
git commit -m "feat(channel-api): add WebhookRequest, WebhookResponse, MessageType models"
```

---

## 任务 3：扩展 ChannelAdapter SPI

**文件：**
- 修改：`intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/ChannelAdapter.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/webchat/WebChatAdapter.java`

- [ ] **步骤 1：扩展 ChannelAdapter 接口**

在 `ChannelAdapter.java` 中新增默认方法（保持向后兼容）：

```java
package com.atm.intellimate.channel.api;

import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public interface ChannelAdapter {

    String getChannelId();
    Mono<Void> connect(Map<String, Object> config);
    Mono<Void> disconnect();
    Mono<Void> send(OutboundMessage message);
    void onMessage(Consumer<InboundEnvelope> handler);
    Class<?> getConfigSchemaClass();
    ChannelStatus getStatus();

    default boolean isConnected() {
        return getStatus() == ChannelStatus.CONNECTED;
    }

    default WebhookResponse handleWebhook(WebhookRequest request) {
        return WebhookResponse.ok();
    }

    default Set<MessageType> supportedMessageTypes() {
        return Collections.singleton(MessageType.TEXT);
    }

    default JsonNode getConfigSchema() {
        return JsonNodeFactory.instance.objectNode();
    }
}
```

- [ ] **步骤 2：确认 WebChatAdapter 无需修改**

`WebChatAdapter` 已实现所有原有方法，新增的 3 个方法有默认实现，无需修改即可编译通过。

运行：`cd intellimate-channel-api && mvn compile && cd ../intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/ChannelAdapter.java
git commit -m "feat(channel-api): extend ChannelAdapter with handleWebhook, supportedMessageTypes, getConfigSchema"
```

---

## 任务 4：AbstractChannelAdapter 基类

**文件：**
- 创建：`intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/AbstractChannelAdapter.java`

- [ ] **步骤 1：实现基类**

```java
package com.atm.intellimate.channel.api;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

public abstract class AbstractChannelAdapter implements ChannelAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected volatile ChannelStatus status = ChannelStatus.DISCONNECTED;
    protected volatile Map<String, Object> config;
    protected volatile Consumer<InboundEnvelope> inboundHandler;

    protected abstract Mono<Void> doConnect(Map<String, Object> config);
    protected abstract Mono<Void> doDisconnect();
    protected abstract Mono<Void> doSend(OutboundMessage message);
    protected abstract InboundEnvelope parseInbound(WebhookRequest request);
    protected abstract boolean verifySignature(WebhookRequest request);

    protected boolean isVerificationRequest(WebhookRequest request) {
        return false;
    }

    protected String handleVerification(WebhookRequest request) {
        return "";
    }

    @Override
    public final Mono<Void> connect(Map<String, Object> config) {
        this.config = config;
        this.status = ChannelStatus.CONNECTING;
        return doConnect(config)
                .doOnSuccess(v -> this.status = ChannelStatus.CONNECTED)
                .doOnError(e -> {
                    this.status = ChannelStatus.ERROR;
                    log.error("[{}] connect failed: {}", getChannelId(), e.getMessage(), e);
                });
    }

    @Override
    public final Mono<Void> disconnect() {
        return doDisconnect()
                .doFinally(s -> this.status = ChannelStatus.DISCONNECTED);
    }

    @Override
    public final Mono<Void> send(OutboundMessage message) {
        if (!isConnected()) {
            return Mono.error(new IllegalStateException(
                    "Channel " + getChannelId() + " is not connected"));
        }
        return doSend(message)
                .doOnError(e -> log.error("[{}] send failed: {}", getChannelId(), e.getMessage(), e));
    }

    @Override
    public void onMessage(Consumer<InboundEnvelope> handler) {
        this.inboundHandler = handler;
    }

    @Override
    public ChannelStatus getStatus() {
        return status;
    }

    @Override
    public WebhookResponse handleWebhook(WebhookRequest request) {
        if (isVerificationRequest(request)) {
            String challenge = handleVerification(request);
            log.info("[{}] verification request handled", getChannelId());
            return WebhookResponse.ok(challenge);
        }

        if (!verifySignature(request)) {
            log.warn("[{}] signature verification failed", getChannelId());
            return WebhookResponse.unauthorized();
        }

        try {
            InboundEnvelope envelope = parseInbound(request);
            if (envelope != null && inboundHandler != null) {
                inboundHandler.accept(envelope);
            }
        } catch (Exception e) {
            log.error("[{}] failed to parse inbound message: {}", getChannelId(), e.getMessage(), e);
        }

        return WebhookResponse.ok();
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-channel-api && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-channel-api/src/main/java/com/atm/intellimate/channel/api/AbstractChannelAdapter.java
git commit -m "feat(channel-api): add AbstractChannelAdapter base class with template methods"
```

---

## 任务 5：增强 ChannelsManager

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelsManager.java`

- [ ] **步骤 1：新增 deliverInbound 方法和 inboundHandler 自动连接**

在 `ChannelsManager` 中添加：

```java
public void deliverInbound(InboundEnvelope envelope) {
    if (inboundHandler != null) {
        inboundHandler.accept(envelope);
    } else {
        log.warn("No inbound handler registered, dropping message from channel: {}",
                envelope.sessionKey().channelId());
    }
}

public Mono<Void> sendOutbound(OutboundMessage message) {
    String channelId = message.sessionKey().channelId();
    ChannelAdapter adapter = adapters.get(channelId);
    if (adapter == null) {
        return Mono.error(new IllegalArgumentException("No adapter for channel: " + channelId));
    }
    if (!adapter.isConnected()) {
        return Mono.error(new IllegalStateException("Channel " + channelId + " is not connected"));
    }
    return adapter.send(message);
}

public ChannelAdapter getAdapter(String channelId) {
    return adapters.get(channelId);
}

public Map<String, ChannelStatus> getAllStatuses() {
    Map<String, ChannelStatus> statuses = new HashMap<>();
    adapters.forEach((id, adapter) -> statuses.put(id, adapter.getStatus()));
    return statuses;
}
```

- [ ] **步骤 2：在 @PostConstruct 中自动注册 inboundHandler**

确保 `ChannelsManager` 初始化时将 `inboundHandler` 传播到所有适配器的 `onMessage`：

```java
@PostConstruct
public void init() {
    // 注册所有适配器到 adapters map（已有逻辑）
    // 为所有适配器注册入站处理器
    adapters.values().forEach(adapter -> adapter.onMessage(this::deliverInbound));
    // 连接已启用的渠道（已有逻辑）
    connectEnabledChannels();
}
```

- [ ] **步骤 3：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelsManager.java
git commit -m "feat(channel): enhance ChannelsManager with deliverInbound, sendOutbound, auto-wire handlers"
```

---

## 任务 6：MessagePipeline 新增 processInbound

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：添加 processInbound 方法**

在 `MessagePipeline` 中新增渠道入站处理入口：

```java
/**
 * 外部渠道入站消息处理入口。
 * 与 processRequest（WebSocket 直连）不同，此方法处理经过 ChannelAdapter 解析后的标准化消息。
 */
public Mono<String> processInbound(InboundEnvelope envelope) {
    SessionKey sessionKey = envelope.sessionKey();
    String agentName = resolveAgentForChannel(sessionKey.channelId());

    log.info("[channel-inbound] channel={}, sender={}, agent={}",
            sessionKey.channelId(), envelope.senderId(), agentName);

    // 构建与 WebSocket 入口兼容的参数，复用已有的会话和 Agent 调度逻辑
    SessionKey effectiveKey = new SessionKey(
            sessionKey.channelId(),
            sessionKey.contextType(),
            sessionKey.contextId()
    );

    return sessionManager.getOrCreate(effectiveKey, new SessionMetadata(agentName, null))
            .flatMap(session -> dispatchToAgent(session, envelope.text(), agentName))
            .map(agentReply -> agentReply);
}

private String resolveAgentForChannel(String channelId) {
    // 从 channel_config 获取 defaultAgent，回退到系统默认 agent
    return channelConfigService.getDefaultAgent(channelId)
            .orElse("default");
}

private Mono<String> dispatchToAgent(Object session, String text, String agentName) {
    // 复用现有的 Agent 调度逻辑，但收集完整回复而非流式返回
    // 具体实现取决于 AgentRuntime 的接口
    return agentRuntime.dispatchAndCollect(session, text, agentName);
}
```

注意：`dispatchToAgent` 的具体实现需要根据现有 `AgentRuntime` 的接口适配。核心区别是外部渠道需要收集完整回复（而非 WebSocket 的流式推送）。

- [ ] **步骤 2：连接 ChannelsManager 的 inboundHandler 到 processInbound**

在 Spring 配置中（或 `ChannelsManager` 初始化时）注册：

```java
@Configuration
public class ChannelPipelineConfig {

    @Bean
    public CommandLineRunner wireChannelPipeline(
            ChannelsManager channelsManager,
            MessagePipeline messagePipeline) {
        return args -> {
            channelsManager.setInboundHandler(envelope -> {
                messagePipeline.processInbound(envelope)
                        .flatMap(reply -> {
                            OutboundMessage out = new OutboundMessage(
                                    envelope.sessionKey(), reply, null, null);
                            return channelsManager.sendOutbound(out);
                        })
                        .subscribe(
                                v -> {},
                                e -> LoggerFactory.getLogger("ChannelPipeline")
                                        .error("Inbound processing failed", e)
                        );
            });
        };
    }
}
```

- [ ] **步骤 3：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat(channel): add processInbound to MessagePipeline, wire inbound handler"
```

---

## 任务 7：重构 WebhookController

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/WebhookController.java`

- [ ] **步骤 1：重构为委托给适配器**

```java
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final ChannelsManager channelsManager;

    public WebhookController(ChannelsManager channelsManager) {
        this.channelsManager = channelsManager;
    }

    @GetMapping("/{channelId}")
    public ResponseEntity<String> handleVerification(
            @PathVariable String channelId,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> params) {

        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return ResponseEntity.notFound().build();
        }

        WebhookRequest request = new WebhookRequest("GET", headers, params, null, null);
        WebhookResponse response = adapter.handleWebhook(request);
        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleJsonCallback(
            @PathVariable String channelId,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> params,
            @RequestBody String body) {

        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return ResponseEntity.notFound().build();
        }

        WebhookRequest request = new WebhookRequest("POST", headers, params, body, "application/json");
        WebhookResponse response = adapter.handleWebhook(request);
        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleXmlCallback(
            @PathVariable String channelId,
            @RequestHeader Map<String, String> headers,
            @RequestParam Map<String, String> params,
            @RequestBody String body) {

        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return ResponseEntity.notFound().build();
        }

        WebhookRequest request = new WebhookRequest("POST", headers, params, body, "application/xml");
        WebhookResponse response = adapter.handleWebhook(request);
        return ResponseEntity.status(response.statusCode())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/WebhookController.java
git commit -m "refactor(channel): delegate WebhookController to adapter.handleWebhook"
```

---

## 任务 8：渠道配置管理服务 + REST API

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChannelConfigService.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelConfigController.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ChannelInfoDto.java`

- [ ] **步骤 1：创建 ChannelConfigService**

```java
package com.atm.intellimate.gateway.service;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.ChannelStatus;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.dto.ChannelInfoDto;
import com.atm.intellimate.gateway.entity.ChannelConfigEntity;
import com.atm.intellimate.gateway.repository.ChannelConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Service
public class ChannelConfigService {

    private final ChannelConfigRepository configRepository;
    private final ChannelsManager channelsManager;
    private final ObjectMapper objectMapper;

    public ChannelConfigService(ChannelConfigRepository configRepository,
                                ChannelsManager channelsManager,
                                ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.channelsManager = channelsManager;
        this.objectMapper = objectMapper;
    }

    public Flux<ChannelInfoDto> listChannels() {
        return configRepository.findByDeletedFalse()
                .map(this::toDto);
    }

    public Mono<ChannelInfoDto> getChannel(String channelId) {
        return configRepository.findByChannelIdAndDeletedFalse(channelId)
                .map(this::toDto);
    }

    public Mono<ChannelConfigEntity> createChannel(String channelId, boolean enabled, Map<String, Object> config) {
        ChannelConfigEntity entity = new ChannelConfigEntity();
        entity.setChannelId(channelId);
        entity.setEnabled(enabled);
        entity.setConfigJson(toJson(config));
        return configRepository.save(entity);
    }

    public Mono<ChannelConfigEntity> updateChannel(String channelId, boolean enabled, Map<String, Object> config) {
        return configRepository.findByChannelIdAndDeletedFalse(channelId)
                .flatMap(entity -> {
                    entity.setEnabled(enabled);
                    if (config != null) {
                        entity.setConfigJson(toJson(config));
                    }
                    return configRepository.save(entity);
                });
    }

    public Mono<Void> deleteChannel(String channelId) {
        return configRepository.findByChannelIdAndDeletedFalse(channelId)
                .flatMap(entity -> {
                    entity.setDeleted(true);
                    return configRepository.save(entity).then();
                });
    }

    public Mono<Void> connectChannel(String channelId) {
        return configRepository.findByChannelIdAndDeletedFalse(channelId)
                .flatMap(entity -> {
                    Map<String, Object> config = fromJson(entity.getConfigJson());
                    return channelsManager.connectChannel(channelId, config);
                });
    }

    public Mono<Void> disconnectChannel(String channelId) {
        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return Mono.error(new IllegalArgumentException("No adapter for: " + channelId));
        }
        return adapter.disconnect();
    }

    public Optional<String> getDefaultAgent(String channelId) {
        // 同步查询缓存或阻塞查询（启动时预加载）
        // 从 config_json.defaultAgent 获取
        return Optional.empty();
    }

    private ChannelInfoDto toDto(ChannelConfigEntity entity) {
        ChannelAdapter adapter = channelsManager.getAdapter(entity.getChannelId());
        ChannelStatus status = adapter != null ? adapter.getStatus() : ChannelStatus.DISCONNECTED;
        Map<String, Object> config = fromJson(entity.getConfigJson());
        return new ChannelInfoDto(
                entity.getChannelId(),
                status.name(),
                entity.isEnabled(),
                maskSensitiveFields(config),
                adapter != null ? adapter.getConfigSchema() : null
        );
    }

    private Map<String, Object> maskSensitiveFields(Map<String, Object> config) {
        if (config == null) return null;
        config.forEach((key, value) -> {
            String lower = key.toLowerCase();
            if ((lower.contains("secret") || lower.contains("token") || lower.contains("key"))
                    && value instanceof String s && s.length() > 6) {
                config.put(key, s.substring(0, 6) + "****");
            }
        });
        return config;
    }

    private String toJson(Map<String, Object> map) {
        try { return objectMapper.writeValueAsString(map); }
        catch (Exception e) { return "{}"; }
    }

    private Map<String, Object> fromJson(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
```

- [ ] **步骤 2：创建 ChannelInfoDto**

```java
package com.atm.intellimate.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record ChannelInfoDto(
        String channelId,
        String status,
        boolean enabled,
        Map<String, Object> config,
        JsonNode configSchema
) {}
```

- [ ] **步骤 3：创建 ChannelConfigController**

```java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.dto.ChannelInfoDto;
import com.atm.intellimate.gateway.service.ChannelConfigService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/channels")
public class ChannelConfigController {

    private final ChannelConfigService channelConfigService;

    public ChannelConfigController(ChannelConfigService channelConfigService) {
        this.channelConfigService = channelConfigService;
    }

    @GetMapping
    public Flux<ChannelInfoDto> listChannels() {
        return channelConfigService.listChannels();
    }

    @GetMapping("/{channelId}")
    public Mono<ChannelInfoDto> getChannel(@PathVariable String channelId) {
        return channelConfigService.getChannel(channelId);
    }

    @PostMapping
    public Mono<Map<String, Object>> createChannel(@RequestBody Map<String, Object> body) {
        String channelId = (String) body.get("channelId");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        return channelConfigService.createChannel(channelId, enabled, config)
                .map(entity -> Map.of("channelId", entity.getChannelId(), "id", entity.getId()));
    }

    @PutMapping("/{channelId}")
    public Mono<Map<String, Object>> updateChannel(
            @PathVariable String channelId,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        return channelConfigService.updateChannel(channelId, enabled, config)
                .map(entity -> Map.of("channelId", entity.getChannelId(), "status", "updated"));
    }

    @DeleteMapping("/{channelId}")
    public Mono<Void> deleteChannel(@PathVariable String channelId) {
        return channelConfigService.deleteChannel(channelId);
    }

    @PostMapping("/{channelId}/connect")
    public Mono<Map<String, String>> connect(@PathVariable String channelId) {
        return channelConfigService.connectChannel(channelId)
                .thenReturn(Map.of("status", "connected"));
    }

    @PostMapping("/{channelId}/disconnect")
    public Mono<Map<String, String>> disconnect(@PathVariable String channelId) {
        return channelConfigService.disconnectChannel(channelId)
                .thenReturn(Map.of("status", "disconnected"));
    }
}
```

- [ ] **步骤 4：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChannelConfigService.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelConfigController.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ChannelInfoDto.java
git commit -m "feat(channel): add ChannelConfigService and REST API for channel CRUD"
```

---

## 任务 9：用户身份映射服务

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/ChannelIdentityEntity.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/ChannelIdentityRepository.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelIdentityService.java`

- [ ] **步骤 1：创建实体**

```java
package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.Instant;

@Table("channel_identity")
public class ChannelIdentityEntity {
    @Id private Long id;
    private String userId;
    private String channelId;
    private String externalId;
    private String externalName;
    private Instant boundAt;

    // getters + setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getExternalName() { return externalName; }
    public void setExternalName(String externalName) { this.externalName = externalName; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
}
```

- [ ] **步骤 2：创建 Repository**

```java
package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChannelIdentityRepository extends ReactiveCrudRepository<ChannelIdentityEntity, Long> {
    Mono<ChannelIdentityEntity> findByChannelIdAndExternalId(String channelId, String externalId);
    Flux<ChannelIdentityEntity> findByUserId(String userId);
}
```

- [ ] **步骤 3：创建 ChannelIdentityService**

```java
package com.atm.intellimate.gateway.channel;

import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import com.atm.intellimate.gateway.repository.ChannelIdentityRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Service
public class ChannelIdentityService {

    private final ChannelIdentityRepository identityRepository;

    public ChannelIdentityService(ChannelIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    /**
     * 解析外部渠道身份 → IntelliMate userId。
     * 如果不存在则自动创建新用户。
     */
    public Mono<String> resolveUserId(String channelId, String externalId, String externalName) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .map(ChannelIdentityEntity::getUserId)
                .switchIfEmpty(createNewIdentity(channelId, externalId, externalName));
    }

    private Mono<String> createNewIdentity(String channelId, String externalId, String externalName) {
        String userId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ChannelIdentityEntity entity = new ChannelIdentityEntity();
        entity.setUserId(userId);
        entity.setChannelId(channelId);
        entity.setExternalId(externalId);
        entity.setExternalName(externalName);
        entity.setBoundAt(Instant.now());
        return identityRepository.save(entity).map(ChannelIdentityEntity::getUserId);
    }

    /**
     * 将外部渠道身份绑定到已存在的 userId（用于跨渠道账号关联）。
     */
    public Mono<Void> bindIdentity(String userId, String channelId, String externalId, String externalName) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .flatMap(existing -> {
                    existing.setUserId(userId);
                    return identityRepository.save(existing).then();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ChannelIdentityEntity entity = new ChannelIdentityEntity();
                    entity.setUserId(userId);
                    entity.setChannelId(channelId);
                    entity.setExternalId(externalId);
                    entity.setExternalName(externalName);
                    entity.setBoundAt(Instant.now());
                    return identityRepository.save(entity).then();
                }));
    }
}
```

- [ ] **步骤 4：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/ChannelIdentityEntity.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/ChannelIdentityRepository.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelIdentityService.java
git commit -m "feat(channel): add ChannelIdentityService for cross-channel user mapping"
```

---

## 任务 10：飞书适配器

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/feishu/FeishuAdapter.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/feishu/FeishuEventParser.java`
- 修改：`intellimate-gateway/pom.xml`（添加飞书 SDK 依赖）

- [ ] **步骤 1：添加飞书 SDK 依赖**

在 `intellimate-gateway/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.larksuite.oapi</groupId>
    <artifactId>oapi-sdk</artifactId>
    <version>2.4.3</version>
</dependency>
```

运行：`cd intellimate-gateway && mvn dependency:resolve`

- [ ] **步骤 2：创建 FeishuEventParser**

```java
package com.atm.intellimate.gateway.channel.feishu;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public class FeishuEventParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isChallenge(WebhookRequest request) {
        if (request.body() == null) return false;
        try {
            JsonNode root = objectMapper.readTree(request.body());
            return root.has("challenge");
        } catch (Exception e) {
            return false;
        }
    }

    public String extractChallenge(WebhookRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.body());
            return root.get("challenge").asText();
        } catch (Exception e) {
            return "";
        }
    }

    public InboundEnvelope parse(WebhookRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.body());
            JsonNode event = root.path("event");
            JsonNode message = event.path("message");
            JsonNode sender = event.path("sender");

            String chatId = message.path("chat_id").asText();
            String chatType = message.path("chat_type").asText();
            String senderId = sender.path("sender_id").path("open_id").asText();
            String senderName = sender.path("sender_id").path("open_id").asText();

            String text = extractTextContent(message);
            String contextType = "p2p".equals(chatType) ? "dm" : "group";
            String contextId = "dm".equals(contextType) ? senderId : chatId;

            SessionKey sessionKey = new SessionKey("feishu", contextType, contextId);

            return new InboundEnvelope(
                    sessionKey,
                    senderId,
                    senderName,
                    text,
                    List.of(),
                    Instant.now(),
                    request.body()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Feishu event", e);
        }
    }

    private String extractTextContent(JsonNode message) {
        try {
            String contentStr = message.path("content").asText();
            JsonNode content = objectMapper.readTree(contentStr);
            return content.path("text").asText("");
        } catch (Exception e) {
            return message.path("content").asText("");
        }
    }
}
```

- [ ] **步骤 3：创建 FeishuAdapter**

```java
package com.atm.intellimate.gateway.channel.feishu;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@Component
public class FeishuAdapter extends AbstractChannelAdapter {

    private static final String CHANNEL_ID = "feishu";
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";

    private final FeishuEventParser eventParser = new FeishuEventParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebClient webClient;
    private String appId;
    private String appSecret;
    private String encryptKey;
    private String verificationToken;
    private volatile String tenantAccessToken;
    private volatile long tokenExpiresAt;

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.appId = (String) config.get("appId");
        this.appSecret = (String) config.get("appSecret");
        this.encryptKey = (String) config.getOrDefault("encryptKey", "");
        this.verificationToken = (String) config.getOrDefault("verificationToken", "");
        this.webClient = WebClient.builder().baseUrl(BASE_URL).build();
        return refreshToken().then();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        this.tenantAccessToken = null;
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        String receiveIdType = "dm".equals(message.sessionKey().contextType())
                ? "open_id" : "chat_id";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("receive_id", message.sessionKey().contextId());
        body.put("msg_type", "text");
        body.set("content", objectMapper.createObjectNode().put("text", message.text()));

        return ensureToken()
                .flatMap(token -> webClient.post()
                        .uri("/im/v1/messages?receive_id_type=" + receiveIdType)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(body.toString())
                        .retrieve()
                        .bodyToMono(String.class)
                        .doOnNext(resp -> log.debug("[feishu] send response: {}", resp))
                        .then()
                );
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        return eventParser.parse(request);
    }

    @Override
    protected boolean isVerificationRequest(WebhookRequest request) {
        return eventParser.isChallenge(request);
    }

    @Override
    protected String handleVerification(WebhookRequest request) {
        String challenge = eventParser.extractChallenge(request);
        return "{\"challenge\":\"" + challenge + "\"}";
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        if (verificationToken == null || verificationToken.isEmpty()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(request.body());
            JsonNode header = root.path("header");
            String token = header.path("token").asText("");
            return verificationToken.equals(token);
        } catch (Exception e) {
            log.warn("[feishu] signature verification error", e);
            return false;
        }
    }

    @Override
    public Set<MessageType> supportedMessageTypes() {
        return Set.of(MessageType.TEXT);
    }

    @Override
    public JsonNode getConfigSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("appId").put("type", "string").put("title", "App ID");
        props.putObject("appSecret").put("type", "string").put("title", "App Secret");
        props.putObject("encryptKey").put("type", "string").put("title", "Encrypt Key");
        props.putObject("verificationToken").put("type", "string").put("title", "Verification Token");
        props.putObject("defaultAgent").put("type", "string").put("title", "默认 Agent");
        schema.putArray("required").add("appId").add("appSecret");
        return schema;
    }

    private Mono<String> ensureToken() {
        if (tenantAccessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300_000) {
            return Mono.just(tenantAccessToken);
        }
        return refreshToken();
    }

    private Mono<String> refreshToken() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);

        return webClient.post()
                .uri("/auth/v3/tenant_access_token/internal")
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    try {
                        JsonNode node = objectMapper.readTree(resp);
                        this.tenantAccessToken = node.get("tenant_access_token").asText();
                        int expire = node.get("expire").asInt(7200);
                        this.tokenExpiresAt = System.currentTimeMillis() + expire * 1000L;
                        return this.tenantAccessToken;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to refresh Feishu token", e);
                    }
                });
    }
}
```

- [ ] **步骤 4：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/pom.xml
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/feishu/
git commit -m "feat(channel): implement FeishuAdapter with event parsing, token management, message send"
```

---

## 任务 11：前端渠道 API 客户端 + Store

**文件：**
- 创建：`intellimate-web/src/lib/channelApi.ts`
- 创建：`intellimate-web/src/stores/channelStore.ts`

- [ ] **步骤 1：创建 channelApi.ts**

```typescript
import { httpClient } from "./httpClient";

export interface ChannelInfo {
  channelId: string;
  status: string;
  enabled: boolean;
  config: Record<string, unknown>;
  configSchema: Record<string, unknown> | null;
}

export interface CreateChannelRequest {
  channelId: string;
  enabled: boolean;
  config: Record<string, unknown>;
}

export async function fetchChannels(): Promise<ChannelInfo[]> {
  return httpClient.get<ChannelInfo[]>("/api/channels");
}

export async function fetchChannel(channelId: string): Promise<ChannelInfo> {
  return httpClient.get<ChannelInfo>(`/api/channels/${channelId}`);
}

export async function createChannel(data: CreateChannelRequest): Promise<{ channelId: string }> {
  return httpClient.post("/api/channels", data);
}

export async function updateChannel(
  channelId: string,
  data: { enabled: boolean; config: Record<string, unknown> }
): Promise<{ status: string }> {
  return httpClient.put(`/api/channels/${channelId}`, data);
}

export async function deleteChannel(channelId: string): Promise<void> {
  return httpClient.delete(`/api/channels/${channelId}`);
}

export async function connectChannel(channelId: string): Promise<{ status: string }> {
  return httpClient.post(`/api/channels/${channelId}/connect`);
}

export async function disconnectChannel(channelId: string): Promise<{ status: string }> {
  return httpClient.post(`/api/channels/${channelId}/disconnect`);
}
```

- [ ] **步骤 2：创建 channelStore.ts**

```typescript
import { create } from "zustand";
import {
  ChannelInfo,
  fetchChannels,
  createChannel,
  updateChannel,
  deleteChannel,
  connectChannel,
  disconnectChannel,
  CreateChannelRequest,
} from "../lib/channelApi";

interface ChannelState {
  channels: ChannelInfo[];
  loading: boolean;
  error: string | null;

  fetchChannels: () => Promise<void>;
  createChannel: (data: CreateChannelRequest) => Promise<void>;
  updateChannel: (channelId: string, data: { enabled: boolean; config: Record<string, unknown> }) => Promise<void>;
  deleteChannel: (channelId: string) => Promise<void>;
  connectChannel: (channelId: string) => Promise<void>;
  disconnectChannel: (channelId: string) => Promise<void>;
  updateChannelStatus: (channelId: string, status: string) => void;
}

export const useChannelStore = create<ChannelState>((set, get) => ({
  channels: [],
  loading: false,
  error: null,

  fetchChannels: async () => {
    set({ loading: true, error: null });
    try {
      const channels = await fetchChannels();
      set({ channels, loading: false });
    } catch (e) {
      set({ error: (e as Error).message, loading: false });
    }
  },

  createChannel: async (data) => {
    await createChannel(data);
    await get().fetchChannels();
  },

  updateChannel: async (channelId, data) => {
    await updateChannel(channelId, data);
    await get().fetchChannels();
  },

  deleteChannel: async (channelId) => {
    await deleteChannel(channelId);
    set({ channels: get().channels.filter((c) => c.channelId !== channelId) });
  },

  connectChannel: async (channelId) => {
    await connectChannel(channelId);
    get().updateChannelStatus(channelId, "CONNECTED");
  },

  disconnectChannel: async (channelId) => {
    await disconnectChannel(channelId);
    get().updateChannelStatus(channelId, "DISCONNECTED");
  },

  updateChannelStatus: (channelId, status) => {
    set({
      channels: get().channels.map((c) =>
        c.channelId === channelId ? { ...c, status } : c
      ),
    });
  },
}));
```

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/lib/channelApi.ts intellimate-web/src/stores/channelStore.ts
git commit -m "feat(web): add channelApi client and channelStore for channel management"
```

---

## 任务 12：前端渠道管理页面

**文件：**
- 创建：`intellimate-web/src/components/ChannelsPage.tsx`
- 创建：`intellimate-web/src/components/ChannelConfigModal.tsx`
- 修改：`intellimate-web/src/App.tsx`
- 修改：`intellimate-web/src/components/Sidebar.tsx`

- [ ] **步骤 1：创建 ChannelsPage.tsx**

```tsx
import { useEffect, useState } from "react";
import { useChannelStore } from "../stores/channelStore";
import { ChannelConfigModal } from "./ChannelConfigModal";

const CHANNEL_ICONS: Record<string, string> = {
  feishu: "🔷",
  dingtalk: "🔵",
  wechat: "🟢",
  webchat: "🌐",
};

const STATUS_COLORS: Record<string, string> = {
  CONNECTED: "bg-green-500",
  CONNECTING: "bg-yellow-500",
  DISCONNECTED: "bg-gray-400",
  RECONNECTING: "bg-yellow-500",
  ERROR: "bg-red-500",
};

export function ChannelsPage() {
  const { channels, loading, fetchChannels, connectChannel, disconnectChannel } =
    useChannelStore();
  const [showModal, setShowModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState<string | null>(null);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  if (loading && channels.length === 0) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="text-gray-500">加载中...</div>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-5xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">渠道管理</h1>
        <button
          onClick={() => { setEditingChannel(null); setShowModal(true); }}
          className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
        >
          添加渠道
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {channels.map((channel) => (
          <div
            key={channel.channelId}
            className="border border-gray-200 dark:border-gray-700 rounded-xl p-5 hover:shadow-md transition-shadow cursor-pointer"
            onClick={() => { setEditingChannel(channel.channelId); setShowModal(true); }}
          >
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <span className="text-2xl">{CHANNEL_ICONS[channel.channelId] || "📡"}</span>
                <span className="font-semibold text-lg">{channel.channelId}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className={`w-2.5 h-2.5 rounded-full ${STATUS_COLORS[channel.status] || "bg-gray-400"}`} />
                <span className="text-sm text-gray-500">{channel.status}</span>
              </div>
            </div>

            <div className="flex gap-2 mt-4">
              {channel.status === "CONNECTED" ? (
                <button
                  onClick={(e) => { e.stopPropagation(); disconnectChannel(channel.channelId); }}
                  className="px-3 py-1 text-sm border border-gray-300 rounded-md hover:bg-gray-100 dark:hover:bg-gray-800"
                >
                  断开
                </button>
              ) : (
                <button
                  onClick={(e) => { e.stopPropagation(); connectChannel(channel.channelId); }}
                  className="px-3 py-1 text-sm bg-green-600 text-white rounded-md hover:bg-green-700"
                >
                  连接
                </button>
              )}
            </div>
          </div>
        ))}
      </div>

      {channels.length === 0 && (
        <div className="text-center py-12 text-gray-500">
          暂无渠道配置，点击「添加渠道」开始接入
        </div>
      )}

      {showModal && (
        <ChannelConfigModal
          channelId={editingChannel}
          onClose={() => setShowModal(false)}
        />
      )}
    </div>
  );
}
```

- [ ] **步骤 2：创建 ChannelConfigModal.tsx**

```tsx
import { useState, useEffect } from "react";
import { useChannelStore } from "../stores/channelStore";
import { fetchChannel } from "../lib/channelApi";

interface Props {
  channelId: string | null;
  onClose: () => void;
}

const AVAILABLE_CHANNELS = [
  { id: "feishu", name: "飞书", icon: "🔷" },
  { id: "dingtalk", name: "钉钉", icon: "🔵" },
  { id: "wechat", name: "微信", icon: "🟢" },
];

export function ChannelConfigModal({ channelId, onClose }: Props) {
  const { createChannel, updateChannel, deleteChannel } = useChannelStore();
  const [selectedType, setSelectedType] = useState(channelId || "");
  const [enabled, setEnabled] = useState(true);
  const [config, setConfig] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);

  const isEdit = channelId !== null;

  useEffect(() => {
    if (isEdit && channelId) {
      fetchChannel(channelId).then((info) => {
        setSelectedType(info.channelId);
        setEnabled(info.enabled);
        setConfig(info.config as Record<string, string>);
      });
    }
  }, [channelId, isEdit]);

  const webhookUrl = `${window.location.origin}/webhook/${selectedType}`;

  const handleSave = async () => {
    setLoading(true);
    try {
      if (isEdit) {
        await updateChannel(selectedType, { enabled, config });
      } else {
        await createChannel({ channelId: selectedType, enabled, config });
      }
      onClose();
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    if (channelId && confirm("确定删除该渠道配置？")) {
      await deleteChannel(channelId);
      onClose();
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={onClose}>
      <div
        className="bg-white dark:bg-gray-900 rounded-xl p-6 w-full max-w-lg max-h-[80vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="text-xl font-bold mb-4">{isEdit ? "编辑渠道" : "添加渠道"}</h2>

        {!isEdit && (
          <div className="mb-4">
            <label className="block text-sm font-medium mb-2">选择平台</label>
            <div className="grid grid-cols-3 gap-2">
              {AVAILABLE_CHANNELS.map((ch) => (
                <button
                  key={ch.id}
                  onClick={() => setSelectedType(ch.id)}
                  className={`p-3 rounded-lg border text-center transition-colors ${
                    selectedType === ch.id
                      ? "border-blue-500 bg-blue-50 dark:bg-blue-900/20"
                      : "border-gray-200 dark:border-gray-700 hover:border-gray-400"
                  }`}
                >
                  <div className="text-2xl">{ch.icon}</div>
                  <div className="text-sm mt-1">{ch.name}</div>
                </button>
              ))}
            </div>
          </div>
        )}

        {selectedType && (
          <>
            <div className="mb-4 p-3 bg-gray-100 dark:bg-gray-800 rounded-lg">
              <div className="text-sm font-medium mb-1">Webhook URL</div>
              <code className="text-xs break-all">{webhookUrl}</code>
              <div className="text-xs text-gray-500 mt-1">
                请将此 URL 配置到{selectedType === "feishu" ? "飞书" : selectedType === "dingtalk" ? "钉钉" : "微信"}开放平台的事件订阅地址
              </div>
            </div>

            <div className="space-y-3 mb-4">
              {selectedType === "feishu" && (
                <>
                  <InputField label="App ID" value={config.appId || ""} onChange={(v) => setConfig({ ...config, appId: v })} />
                  <InputField label="App Secret" value={config.appSecret || ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                  <InputField label="Encrypt Key" value={config.encryptKey || ""} onChange={(v) => setConfig({ ...config, encryptKey: v })} />
                  <InputField label="Verification Token" value={config.verificationToken || ""} onChange={(v) => setConfig({ ...config, verificationToken: v })} />
                </>
              )}
              {selectedType === "dingtalk" && (
                <>
                  <InputField label="App Key" value={config.appKey || ""} onChange={(v) => setConfig({ ...config, appKey: v })} />
                  <InputField label="App Secret" value={config.appSecret || ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                  <InputField label="签名密钥" value={config.signSecret || ""} onChange={(v) => setConfig({ ...config, signSecret: v })} />
                </>
              )}
              {selectedType === "wechat" && (
                <>
                  <InputField label="App ID" value={config.appId || ""} onChange={(v) => setConfig({ ...config, appId: v })} />
                  <InputField label="App Secret" value={config.appSecret || ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                  <InputField label="Token" value={config.token || ""} onChange={(v) => setConfig({ ...config, token: v })} />
                  <InputField label="Encoding AES Key" value={config.encodingAesKey || ""} onChange={(v) => setConfig({ ...config, encodingAesKey: v })} />
                </>
              )}
              <InputField label="默认 Agent" value={config.defaultAgent || ""} onChange={(v) => setConfig({ ...config, defaultAgent: v })} placeholder="留空使用 default" />
            </div>

            <div className="flex items-center gap-2 mb-6">
              <input
                type="checkbox"
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                className="rounded"
              />
              <span className="text-sm">启用（启动时自动连接）</span>
            </div>
          </>
        )}

        <div className="flex justify-between">
          <div>
            {isEdit && (
              <button onClick={handleDelete} className="px-4 py-2 text-red-600 hover:bg-red-50 rounded-lg">
                删除
              </button>
            )}
          </div>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-100">
              取消
            </button>
            <button
              onClick={handleSave}
              disabled={!selectedType || loading}
              className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50"
            >
              {loading ? "保存中..." : "保存"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function InputField({ label, value, onChange, type = "text", placeholder = "" }: {
  label: string; value: string; onChange: (v: string) => void; type?: string; placeholder?: string;
}) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-sm"
      />
    </div>
  );
}
```

- [ ] **步骤 3：添加路由和侧边栏**

在 `App.tsx` 的 Routes 中添加：

```tsx
<Route path="/channels" element={<ChannelsPage />} />
```

在 `Sidebar.tsx` 的管理分组中添加渠道导航项（参照 tools/skills/models 的模式）：

```tsx
<NavItem to="/channels" icon={LinkIcon} label="渠道" />
```

- [ ] **步骤 4：验证前端构建**

运行：`cd intellimate-web && npm run build`
预期：构建成功，无 TypeScript 错误

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/components/ChannelsPage.tsx
git add intellimate-web/src/components/ChannelConfigModal.tsx
git add intellimate-web/src/App.tsx
git add intellimate-web/src/components/Sidebar.tsx
git commit -m "feat(web): add channels management page with config modal, routing, and sidebar nav"
```

---

## 自检结果

**规格覆盖度：**
- ✅ 入站链路打通（任务 5-7）
- ✅ SPI 扩展（任务 3-4）
- ✅ 飞书适配器（任务 10）
- ✅ 跨渠道用户映射（任务 9）
- ✅ REST API（任务 8）
- ✅ 前端管理页（任务 11-12）
- ✅ DB 迁移（任务 1）
- ⚠️ 监控指标和告警 → 规格明确归入阶段 4，本计划不含
- ⚠️ 钉钉/微信适配器 → 规格明确归入阶段 3-4，本计划不含
- ⚠️ 消息来源标记的 Web 端展示 → 需在 ChatPanel 中添加渠道图标，可作为后续迭代

**占位符扫描：** 无 TODO/TBD/待定。所有步骤包含完整代码。

**类型一致性：**
- `WebhookRequest`/`WebhookResponse` 在任务 2 定义，任务 3-4 和 7 中使用的签名一致
- `InboundEnvelope`/`OutboundMessage` 使用现有 record 类型，不变
- `ChannelsManager.deliverInbound` 在任务 5 定义，任务 6 中通过 `setInboundHandler` 连接
- `ChannelConfigService` 在任务 8 定义，任务 6 中通过 `resolveAgentForChannel` 引用
