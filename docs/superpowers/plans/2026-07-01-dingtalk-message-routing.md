# 钉钉消息路由与绑定体验优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 优化钉钉绑定码体验、新增群聊主动推送能力、增加多账号绑定校验。

**架构：** 在现有 `channel_identity` 机制上增强绑定流程（格式容错、重复校验、实时通知），新增 `channel_group` 表支持群聊 ↔ Agent 绑定，`ChatInjectionService` 增加群聊推送路径。前端在 `ChannelsPage` 增强绑定交互，`ChannelConfigModal` 新增群聊管理 Tab。

**技术栈：** Java 21 / Spring WebFlux / R2DBC / Flyway / React 19 / TypeScript / Zustand

**设计规格：** `docs/superpowers/specs/2026-07-01-dingtalk-message-routing-design.md`

---

## 文件结构

### 后端（新增）

| 文件 | 职责 |
|------|------|
| `V41__add_channel_group.sql` | Flyway 迁移，创建 `channel_group` 表 |
| `ChannelGroupEntity.java` | 群信息实体（R2DBC `@Table`） |
| `ChannelGroupRepository.java` | 群信息 R2DBC 响应式仓库 |
| `ChannelGroupService.java` | 群管理业务逻辑（CRUD + Agent 绑定） |
| `ChannelGroupController.java` | 群管理 REST API |

### 后端（修改）

| 文件 | 修改内容 |
|------|---------|
| `ChannelPipelineConfig.java` | 绑定码格式容错 + 重复绑定校验 + 首次互动引导 + 绑定成功 WS 通知 |
| `ChannelIdentityService.java` | 新增 `isExternalIdBoundToOtherUser()` 检查方法 |
| `DingtalkStreamAdapter.java` | 群消息时记录群信息 |
| `ChatInjectionService.java` | 主动推送增加群聊发送路径 |

### 前端（修改）

| 文件 | 修改内容 |
|------|---------|
| `useWebSocket.ts` | 处理 `binding.success` 事件 |
| `ChannelsPage.tsx` | 绑定码倒计时 + 操作步骤优化 |
| `ChannelConfigModal.tsx` | 新增「群聊」Tab |
| `channelApi.ts` | 新增群管理 API 封装 |

---

### 任务 1：Flyway 迁移 — 创建 channel_group 表

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V41__add_channel_group.sql`

- [ ] **步骤 1：编写迁移脚本**

```sql
CREATE TABLE IF NOT EXISTS channel_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id VARCHAR(50) NOT NULL,
    group_id VARCHAR(200) NOT NULL,
    group_name VARCHAR(200),
    agent_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_channel_group (channel_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **步骤 2：启动应用验证迁移**

运行：`cd intellimate-gateway && mvn spring-boot:run`（或 Docker 启动）
预期：日志中出现 `Successfully applied 1 migration to schema` 且无报错

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V41__add_channel_group.sql
git commit -m "feat: add channel_group table for group-agent binding"
```

---

### 任务 2：ChannelGroupEntity + Repository

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/ChannelGroupEntity.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/ChannelGroupRepository.java`

- [ ] **步骤 1：编写 ChannelGroupEntity**

```java
package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("channel_group")
public class ChannelGroupEntity {

    @Id
    private Long id;
    private String channelId;
    private String groupId;
    private String groupName;
    private String agentName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **步骤 2：编写 ChannelGroupRepository**

```java
package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChannelGroupRepository extends ReactiveCrudRepository<ChannelGroupEntity, Long> {

    Flux<ChannelGroupEntity> findByChannelId(String channelId);

    Mono<ChannelGroupEntity> findByChannelIdAndGroupId(String channelId, String groupId);

    Flux<ChannelGroupEntity> findByAgentName(String agentName);
}
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/ChannelGroupEntity.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/ChannelGroupRepository.java
git commit -m "feat: add ChannelGroupEntity and repository"
```

---

### 任务 3：绑定码格式容错

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java:117-133`

- [ ] **步骤 1：修改 tryBindingCode 方法，增加文本预处理**

在 `ChannelPipelineConfig.java` 的 `tryBindingCode` 方法中，在正则匹配前对文本做预处理。

将现有代码：

```java
    private static java.util.Optional<Mono<String>> tryBindingCode(
            InboundEnvelope envelope,
            ChannelBindingCodeService bindingCodeService,
            ChannelIdentityService identityService) {
        String text = envelope.text() != null ? envelope.text().trim() : "";
        if (!BINDING_CODE_PATTERN.matcher(text).matches()) {
            return java.util.Optional.empty();
        }
```

替换为：

```java
    private static java.util.Optional<Mono<String>> tryBindingCode(
            InboundEnvelope envelope,
            ChannelBindingCodeService bindingCodeService,
            ChannelIdentityService identityService) {
        String text = envelope.text() != null ? envelope.text().trim() : "";
        String normalized = normalizeBindingInput(text);
        if (!BINDING_CODE_PATTERN.matcher(normalized).matches()) {
            return java.util.Optional.empty();
        }
```

然后在 `bindingCodeService.lookup(text)` 调用处将 `text` 改为 `normalized`。

- [ ] **步骤 2：新增 normalizeBindingInput 方法**

在 `ChannelPipelineConfig` 类中添加：

```java
    private static String normalizeBindingInput(String text) {
        String s = text.strip();
        if (s.startsWith("bind ") || s.startsWith("bind\t")) {
            s = s.substring(5);
        } else if (s.startsWith("绑定 ") || s.startsWith("绑定\t")) {
            s = s.substring(3);
        } else if (s.startsWith("绑定")) {
            s = s.substring(2);
        }
        return s.replaceAll("\\s+", "").strip();
    }
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat: binding code format tolerance (bind/绑定 prefix, spaces)"
```

---

### 任务 4：多账号绑定校验

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelIdentityService.java:35-61`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java:117-133`

- [ ] **步骤 1：在 ChannelIdentityService 中新增校验方法**

在 `ChannelIdentityService.java` 中添加：

```java
    /**
     * 检查某个外部身份是否已绑定到其他用户。
     * 返回已绑定的 userId（如果存在且与目标 userId 不同）。
     */
    public Mono<String> findBoundUserId(String channelId, String externalId) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .map(ChannelIdentityEntity::getUserId)
                .defaultIfEmpty("");
    }
```

- [ ] **步骤 2：修改 tryBindingCode，增加重复绑定校验**

在 `ChannelPipelineConfig.java` 的 `tryBindingCode` 方法中，将绑定逻辑从直接调用 `bindIdentity` 改为先检查：

将现有的 `return bindingCodeService.lookup(normalized)` 块替换为：

```java
        return bindingCodeService.lookup(normalized)
                .map(entry -> identityService.findBoundUserId(
                                envelope.sessionKey().channelId(), envelope.senderId())
                        .flatMap(existingUserId -> {
                            if (!existingUserId.isEmpty() && !existingUserId.equals(entry.userId())) {
                                return Mono.just("绑定失败：该账号已被其他 Web 用户绑定");
                            }
                            if (existingUserId.equals(entry.userId())) {
                                bindingCodeService.consume(normalized);
                                return Mono.just("已绑定，无需重复操作");
                            }
                            return identityService.bindIdentity(
                                            entry.userId(),
                                            envelope.sessionKey().channelId(),
                                            envelope.senderId(),
                                            envelope.senderName())
                                    .doOnSuccess(v -> bindingCodeService.consume(normalized))
                                    .thenReturn("绑定成功！你的账号已与 Web 端关联，后续消息将自动同步。");
                        }));
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelIdentityService.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat: prevent one DingTalk account binding to multiple Web users"
```

---

### 任务 5：首次互动引导

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java:84-115`

当未绑定 Web 账号的钉钉用户首次与机器人互动时，在 Agent 回复后附加绑定引导文本。

- [ ] **步骤 1：在 resolveIdentityAndProcess 中增加绑定引导逻辑**

在 `resolveIdentityAndProcess` 方法中，当消息是 DM 且来自外部渠道时，检查该 `channel_identity` 的 `userId` 是否只有当前渠道的身份（没有 webchat 身份意味着未绑定 Web 账号）。

在 `messagePipeline.processInbound` 返回回复后，追加引导文本。修改 `resolveIdentityAndProcess` 方法，将 `identityService` 从只返回 `userId` 改为同时携带绑定状态信息。

实现方式：在 `identityService.resolveUserId` 之后，额外查询该 userId 是否已有 webchat 绑定：

```java
    private static Mono<String> resolveIdentityAndProcess(
            InboundEnvelope envelope,
            ChannelIdentityService identityService,
            ChannelConfigService channelConfigService,
            MessagePipeline messagePipeline) {

        String channelId = envelope.sessionKey().channelId();
        String contextType = envelope.sessionKey().contextType();

        return identityService.resolveUserId(channelId, envelope.senderId(), envelope.senderName())
                .flatMap(userId -> {
                    InboundEnvelope effectiveEnvelope;
                    if (DM_CONTEXT_TYPES.contains(contextType)) {
                        SessionKey unifiedKey = new SessionKey("unified", "dm", userId);
                        effectiveEnvelope = new InboundEnvelope(
                                unifiedKey, envelope.senderId(), envelope.senderName(),
                                envelope.text(), envelope.attachments(), envelope.timestamp(),
                                envelope.rawPayload());
                    } else {
                        effectiveEnvelope = envelope;
                    }

                    Mono<String> replyMono = channelConfigService.getDefaultAgent(channelId)
                            .flatMap(optAgent -> messagePipeline.processInbound(
                                    effectiveEnvelope, optAgent.orElse(null), channelId));

                    if (DM_CONTEXT_TYPES.contains(contextType)
                            && !"webchat".equals(channelId)
                            && !"unified".equals(channelId)) {
                        return replyMono.flatMap(reply ->
                                identityService.listByUserId(userId)
                                        .any(id -> "webchat".equals(id.getChannelId()))
                                        .map(hasWebchat -> hasWebchat ? reply
                                                : reply + "\n\n💡 如需将此账号与 Web 端关联以实现消息同步，请在 Web 端「渠道管理 → 跨渠道身份绑定」中生成绑定码，然后发送给我。"));
                    }
                    return replyMono;
                });
    }
```

注意：这会在**每次**未绑定用户的 DM 对话中附加引导。如需仅首次显示，可在 `channel_identity` 表中加一个 `guided` 标志位。但考虑到实现复杂度和引导文本的非侵入性，建议先每次附加，后续根据用户反馈优化。

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat: append binding guidance for unbound DingTalk users"
```

---

### 任务 6：绑定成功 WebSocket 通知

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java`

此任务在绑定成功后，通过 `SessionRegistry.pushToUser` 向 Web 端推送 `binding.success` 事件。

- [ ] **步骤 1：在 ChannelPipelineConfig 构造函数中注入 SessionRegistry**

当前构造函数参数中没有 `SessionRegistry`，需添加。在 `ChannelPipelineConfig.java` 构造函数中新增参数 `SessionRegistry sessionRegistry`，并将其传给 `tryBindingCode` 方法（需要将 `tryBindingCode` 改为非静态方法，或将 `SessionRegistry` 作为参数传入）。

推荐方式：将 `SessionRegistry` 作为额外参数传给 `tryBindingCode`。

修改构造函数签名和内部调用：

```java
    public ChannelPipelineConfig(ChannelsManager channelsManager,
                                 MessagePipeline messagePipeline,
                                 ChannelMetrics channelMetrics,
                                 ChannelBindingCodeService bindingCodeService,
                                 ChannelIdentityService identityService,
                                 ChannelConfigService channelConfigService,
                                 SessionRegistry sessionRegistry) {
        channelsManager.setInboundHandler(envelope -> {
            // ...
            Mono<String> replyMono = tryBindingCode(envelope, bindingCodeService, identityService, sessionRegistry)
                    .orElseGet(() -> resolveIdentityAndProcess(
                            envelope, identityService, channelConfigService, messagePipeline));
            // ... rest unchanged
        });
    }
```

- [ ] **步骤 2：在绑定成功的回调中推送 WebSocket 事件**

在 `tryBindingCode` 方法的绑定成功分支中，调用 `SessionRegistry` 推送事件。需要根据绑定目标 userId 查找 webchat 身份来获取 DB userId。

在 `bindIdentity` 成功后的 `.doOnSuccess` 回调中添加：

```java
.doOnSuccess(v -> {
    bindingCodeService.consume(normalized);
    // 查找 Web 用户的 DB userId 并推送绑定成功事件
    identityService.listByUserId(entry.userId())
            .filter(id -> "webchat".equals(id.getChannelId()))
            .next()
            .subscribe(webchatId -> {
                try {
                    Long dbUserId = Long.parseLong(webchatId.getExternalId());
                    sessionRegistry.pushToUser(dbUserId, "binding.success", Map.of(
                            "channelId", envelope.sessionKey().channelId(),
                            "externalName", envelope.senderName() != null ? envelope.senderName() : "",
                            "boundAt", java.time.Instant.now().toString()
                    ));
                } catch (NumberFormatException ignored) {}
            });
})
```

需要在文件顶部添加 import：`import java.util.Map;`（如果尚未导入）和 `import com.atm.intellimate.gateway.websocket.SessionRegistry;`。

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat: push binding.success WebSocket event on successful bind"
```

---

### 任务 7：钉钉群消息时记录群信息

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkStreamAdapter.java:224-255`

- [ ] **步骤 1：在 DingtalkStreamAdapter 中添加群信息记录回调**

`DingtalkStreamAdapter` 不应直接依赖 `ChannelGroupRepository`（它是 `@Component` 且与 Spring Data 分层不同），所以通过一个函数式回调（`Consumer<GroupInfo>`）来解耦。

在 `DingtalkStreamAdapter` 类中添加：

```java
    public record GroupInfo(String channelId, String groupId, String groupName) {}

    private volatile Consumer<GroupInfo> groupInfoHandler;

    public void onGroupDiscovered(Consumer<GroupInfo> handler) {
        this.groupInfoHandler = handler;
    }
```

- [ ] **步骤 2：在 handleBotMessage 中，当 contextType 为 group 时调用回调**

在 `handleBotMessage` 方法中，`contextType` 判断为 `"group"` 后，添加：

```java
            if ("group".equals(contextType) && groupInfoHandler != null) {
                String conversationTitle = message.getConversationTitle();
                groupInfoHandler.accept(new GroupInfo(CHANNEL_ID, contextId, conversationTitle));
            }
```

放在 `InboundEnvelope` 构建之前（`contextType` 确定之后）。

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkStreamAdapter.java
git commit -m "feat: emit group discovery callback on DingTalk group messages"
```

---

### 任务 8：ChannelGroupService + Controller

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChannelGroupService.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelGroupController.java`

- [ ] **步骤 1：编写 ChannelGroupService**

```java
package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import com.atm.intellimate.gateway.repository.ChannelGroupRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ChannelGroupService {

    private final ChannelGroupRepository groupRepository;

    public ChannelGroupService(ChannelGroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    public Mono<ChannelGroupEntity> recordGroup(String channelId, String groupId, String groupName) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(existing -> {
                    if (groupName != null && !groupName.equals(existing.getGroupName())) {
                        existing.setGroupName(groupName);
                        existing.setUpdatedAt(LocalDateTime.now());
                        return groupRepository.save(existing);
                    }
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ChannelGroupEntity entity = new ChannelGroupEntity();
                    entity.setChannelId(channelId);
                    entity.setGroupId(groupId);
                    entity.setGroupName(groupName);
                    entity.setCreatedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                }));
    }

    public Flux<ChannelGroupEntity> listByChannel(String channelId) {
        return groupRepository.findByChannelId(channelId);
    }

    public Flux<ChannelGroupEntity> listByAgent(String agentName) {
        return groupRepository.findByAgentName(agentName);
    }

    public Mono<ChannelGroupEntity> bindAgent(String channelId, String groupId, String agentName) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(entity -> {
                    entity.setAgentName(agentName);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group not found")));
    }

    public Mono<ChannelGroupEntity> unbindAgent(String channelId, String groupId) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(entity -> {
                    entity.setAgentName(null);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group not found")));
    }
}
```

- [ ] **步骤 2：编写 ChannelGroupController**

```java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import com.atm.intellimate.gateway.service.ChannelGroupService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/channels/{channelId}/groups")
public class ChannelGroupController {

    private final ChannelGroupService groupService;

    public ChannelGroupController(ChannelGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public Flux<ChannelGroupEntity> listGroups(@PathVariable String channelId) {
        return groupService.listByChannel(channelId);
    }

    @PutMapping("/{groupId}/agent")
    public Mono<ChannelGroupEntity> bindAgent(
            @PathVariable String channelId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        String agentName = body.get("agentName");
        if (agentName == null || agentName.isBlank()) {
            return Mono.error(new IllegalArgumentException("agentName is required"));
        }
        return groupService.bindAgent(channelId, groupId, agentName);
    }

    @DeleteMapping("/{groupId}/agent")
    public Mono<ChannelGroupEntity> unbindAgent(
            @PathVariable String channelId,
            @PathVariable String groupId) {
        return groupService.unbindAgent(channelId, groupId);
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChannelGroupService.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelGroupController.java
git commit -m "feat: add ChannelGroupService and REST API for group management"
```

---

### 任务 9：注册群信息发现回调

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelsManager.java`（或在 `ChannelPipelineConfig` 中注册回调）

此任务将 `DingtalkStreamAdapter.onGroupDiscovered` 回调与 `ChannelGroupService.recordGroup` 连接。

- [ ] **步骤 1：在 ChannelPipelineConfig 构造函数中注册回调**

在 `ChannelPipelineConfig` 构造函数中添加 `ChannelGroupService` 参数并注册回调：

```java
    public ChannelPipelineConfig(ChannelsManager channelsManager,
                                 MessagePipeline messagePipeline,
                                 ChannelMetrics channelMetrics,
                                 ChannelBindingCodeService bindingCodeService,
                                 ChannelIdentityService identityService,
                                 ChannelConfigService channelConfigService,
                                 SessionRegistry sessionRegistry,
                                 ChannelGroupService channelGroupService) {

        // 注册群信息发现回调
        channelsManager.getAdapters().stream()
                .filter(a -> a instanceof DingtalkStreamAdapter)
                .map(a -> (DingtalkStreamAdapter) a)
                .forEach(adapter -> adapter.onGroupDiscovered(info ->
                        channelGroupService.recordGroup(info.channelId(), info.groupId(), info.groupName())
                                .subscribe()));

        channelsManager.setInboundHandler(envelope -> {
            // ... existing logic
        });
    }
```

需要在文件顶部添加 import：
```java
import com.atm.intellimate.gateway.channel.dingtalk.DingtalkStreamAdapter;
import com.atm.intellimate.gateway.service.ChannelGroupService;
```

注意：如果 `ChannelsManager` 没有 `getAdapters()` 方法，需要先添加一个。检查 `ChannelsManager` 是否有暴露已注册适配器列表的方法。如果没有，替代方案是直接在构造函数中注入 `DingtalkStreamAdapter`（通过 `@Autowired(required = false)`）。

替代方案（推荐，更简单）：

```java
    public ChannelPipelineConfig(ChannelsManager channelsManager,
                                 MessagePipeline messagePipeline,
                                 ChannelMetrics channelMetrics,
                                 ChannelBindingCodeService bindingCodeService,
                                 ChannelIdentityService identityService,
                                 ChannelConfigService channelConfigService,
                                 SessionRegistry sessionRegistry,
                                 ChannelGroupService channelGroupService,
                                 @Autowired(required = false) DingtalkStreamAdapter dingtalkStreamAdapter) {

        if (dingtalkStreamAdapter != null) {
            dingtalkStreamAdapter.onGroupDiscovered(info ->
                    channelGroupService.recordGroup(info.channelId(), info.groupId(), info.groupName())
                            .subscribe());
        }
        // ... rest
    }
```

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat: wire group discovery callback to ChannelGroupService"
```

---

### 任务 10：ChatInjectionService 群聊推送

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChatInjectionService.java:96-119`

- [ ] **步骤 1：注入 ChannelGroupRepository**

在 `ChatInjectionService` 构造函数中添加 `ChannelGroupRepository` 参数：

```java
    private final ChannelGroupRepository groupRepository;

    public ChatInjectionService(SessionRegistry sessionRegistry,
                                SessionManager sessionManager,
                                SessionRepository sessionRepository,
                                ChannelsManager channelsManager,
                                ChannelConfigService channelConfigService,
                                ChannelIdentityRepository identityRepository,
                                ChannelGroupRepository groupRepository) {
        // ... existing assignments
        this.groupRepository = groupRepository;
    }
```

添加 import：`import com.atm.intellimate.gateway.repository.ChannelGroupRepository;`

- [ ] **步骤 2：新增 pushToGroups 方法**

在 `ChatInjectionService` 中添加：

```java
    private Mono<Integer> pushToGroups(String agentName, String content) {
        return groupRepository.findByAgentName(agentName)
                .flatMap(group -> {
                    var adapter = channelsManager.getAdapter(group.getChannelId());
                    if (adapter == null || !adapter.isConnected()) {
                        return Mono.just(0);
                    }
                    SessionKey key = new SessionKey(group.getChannelId(), "group", group.getGroupId());
                    OutboundMessage outbound = new OutboundMessage(key, content, Collections.emptyList(), null);
                    return channelsManager.send(outbound)
                            .thenReturn(1)
                            .onErrorResume(e -> {
                                log.warn("Failed to push proactive message to group={} channel={}: {}",
                                        group.getGroupId(), group.getChannelId(), e.getMessage());
                                return Mono.just(0);
                            });
                })
                .reduce(0, Integer::sum);
    }
```

- [ ] **步骤 3：在 injectAgentMessage 中调用 pushToGroups**

在 `injectAgentMessage` 方法中，将：

```java
        Mono<Integer> channelMono = pushToExternalChannels(agentName, content);
```

替换为：

```java
        Mono<Integer> channelMono = pushToExternalChannels(agentName, content);
        Mono<Integer> groupMono = pushToGroups(agentName, content);
```

并将 `Mono.zip` 调用从 `Mono.zip(wsMono, channelMono)` 改为：

```java
        return persistMono
                .then(Mono.zip(wsMono, channelMono, groupMono))
                .map(tuple -> {
                    int total = tuple.getT1() + tuple.getT2() + tuple.getT3();
                    if (total == 0) {
                        log.warn("Proactive message for agent '{}' not delivered: no connected sessions/channels/groups", agentName);
                    }
                    return total;
                });
```

- [ ] **步骤 4：编译验证**

运行：`cd intellimate-gateway && mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChatInjectionService.java
git commit -m "feat: add group chat proactive push in ChatInjectionService"
```

---

### 任务 11：前端 — binding.success 事件处理

**文件：**
- 修改：`intellimate-web/src/hooks/useWebSocket.ts:133-139`
- 修改：`intellimate-web/src/stores/chatStore.ts`（或新增 store 方法）

- [ ] **步骤 1：在 useWebSocket.ts 中添加 binding.success 事件处理**

在 `useWebSocket.ts` 的事件处理 switch 中，在 `case "message.sync"` 后面添加：

```typescript
          case "binding.success": {
            const channelId = event.payload.channelId as string;
            const externalName = event.payload.externalName as string;
            const boundAt = event.payload.boundAt as string;
            window.dispatchEvent(
              new CustomEvent("binding-success", {
                detail: { channelId, externalName, boundAt },
              })
            );
            break;
          }
```

使用 `CustomEvent` 是为了不耦合 chatStore 与绑定逻辑，`ChannelsPage` 可以通过 `addEventListener` 监听并刷新列表。

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-web && npm run build`
预期：无报错

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat: handle binding.success WebSocket event in frontend"
```

---

### 任务 12：前端 — 绑定码 UX 优化

**文件：**
- 修改：`intellimate-web/src/components/ChannelsPage.tsx:148-217`

- [ ] **步骤 1：添加倒计时逻辑**

在 `ChannelsPage` 组件中，添加一个 `useEffect` 用于绑定码倒计时：

```typescript
  useEffect(() => {
    if (codeExpiresIn <= 0 || !bindingCode) return;
    const timer = setInterval(() => {
      setCodeExpiresIn((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          setBindingCode(null);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [bindingCode, codeExpiresIn > 0]);
```

- [ ] **步骤 2：添加 binding-success 事件监听**

在 `ChannelsPage` 组件中添加：

```typescript
  useEffect(() => {
    const handler = () => {
      loadIdentities();
      setBindingCode(null);
    };
    window.addEventListener("binding-success", handler);
    return () => window.removeEventListener("binding-success", handler);
  }, [loadIdentities]);
```

- [ ] **步骤 3：优化绑定码显示区域的操作步骤文案**

将现有的步骤文案（L163-164）：

```tsx
<p className="text-xs text-slate-500 dark:text-slate-400 mb-2">
  步骤：生成绑定码 → 在钉钉/飞书中发送该绑定码 → 绑定完成
</p>
```

替换为更详细的引导：

```tsx
<div className="text-xs text-slate-500 dark:text-slate-400 mb-2 space-y-1">
  <p className="font-medium">绑定步骤：</p>
  <p>1. 点击「生成绑定码」获取 6 位数字码</p>
  <p>2. 在钉钉/飞书中找到机器人，直接发送该绑定码</p>
  <p>3. 收到「绑定成功」提示后，此页面自动更新</p>
</div>
```

- [ ] **步骤 4：倒计时显示秒级精度**

将绑定码旁的过期时间显示（L182-184）从分钟改为秒：

```tsx
<span className="text-[10px] text-blue-400">
  {codeExpiresIn > 60
    ? `${Math.floor(codeExpiresIn / 60)}分${codeExpiresIn % 60}秒`
    : `${codeExpiresIn}秒`}
</span>
```

- [ ] **步骤 5：编译验证**

运行：`cd intellimate-web && npm run build`
预期：无报错

- [ ] **步骤 6：Commit**

```bash
git add intellimate-web/src/components/ChannelsPage.tsx
git commit -m "feat: binding code countdown, auto-refresh on success, better guidance"
```

---

### 任务 13：前端 — channelApi 群管理 API

**文件：**
- 修改：`intellimate-web/src/lib/channelApi.ts`

- [ ] **步骤 1：添加群管理 API 类型和函数**

在 `channelApi.ts` 文件末尾添加：

```typescript
export interface ChannelGroup {
  id: number;
  channelId: string;
  groupId: string;
  groupName: string | null;
  agentName: string | null;
  createdAt: string;
  updatedAt: string;
}

export function listChannelGroups(channelId: string): Promise<ChannelGroup[]> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}/groups`);
}

export function bindGroupAgent(
  channelId: string,
  groupId: string,
  agentName: string,
): Promise<ChannelGroup> {
  return apiFetch(
    `/api/channels/${encodeURIComponent(channelId)}/groups/${encodeURIComponent(groupId)}/agent`,
    {
      method: "PUT",
      body: JSON.stringify({ agentName }),
    },
  );
}

export function unbindGroupAgent(
  channelId: string,
  groupId: string,
): Promise<ChannelGroup> {
  return apiFetch(
    `/api/channels/${encodeURIComponent(channelId)}/groups/${encodeURIComponent(groupId)}/agent`,
    { method: "DELETE" },
  );
}
```

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-web && npm run build`
预期：无报错

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/lib/channelApi.ts
git commit -m "feat: add group management API functions in channelApi"
```

---

### 任务 14：前端 — 渠道配置页群聊管理 Tab

**文件：**
- 修改：`intellimate-web/src/components/ChannelConfigModal.tsx`

- [ ] **步骤 1：添加群聊管理 state 和数据加载**

在 `ChannelConfigModal` 组件中，添加群聊相关 state：

```typescript
import { listChannelGroups, bindGroupAgent, unbindGroupAgent, type ChannelGroup } from "../lib/channelApi";

// 在组件内部
const [activeTab, setActiveTab] = useState<"config" | "groups">("config");
const [groups, setGroups] = useState<ChannelGroup[]>([]);
const [groupsLoading, setGroupsLoading] = useState(false);

const loadGroups = useCallback(async () => {
  if (!channelId) return;
  setGroupsLoading(true);
  try {
    const data = await listChannelGroups(channelId);
    setGroups(data);
  } catch {
    // 静默处理
  } finally {
    setGroupsLoading(false);
  }
}, [channelId]);

useEffect(() => {
  if (activeTab === "groups" && channelId) {
    loadGroups();
  }
}, [activeTab, channelId, loadGroups]);
```

- [ ] **步骤 2：添加 Tab 切换 UI**

在 Modal 标题下方、配置表单上方，添加 Tab 切换（仅在编辑模式下显示，新建时不显示群聊 Tab）：

```tsx
{channelId && (
  <div className="flex border-b border-slate-200 dark:border-slate-700 mb-4">
    <button
      onClick={() => setActiveTab("config")}
      className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
        activeTab === "config"
          ? "border-blue-500 text-blue-600 dark:text-blue-400"
          : "border-transparent text-slate-500 hover:text-slate-700"
      }`}
    >
      配置
    </button>
    <button
      onClick={() => setActiveTab("groups")}
      className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
        activeTab === "groups"
          ? "border-blue-500 text-blue-600 dark:text-blue-400"
          : "border-transparent text-slate-500 hover:text-slate-700"
      }`}
    >
      群聊
    </button>
  </div>
)}
```

- [ ] **步骤 3：添加群聊列表 UI**

在 Tab 内容区域，当 `activeTab === "groups"` 时显示：

```tsx
{activeTab === "groups" && (
  <div className="space-y-3">
    {groupsLoading ? (
      <div className="flex justify-center py-8">
        <Loader2 size={20} className="animate-spin text-slate-400" />
      </div>
    ) : groups.length === 0 ? (
      <div className="text-center py-8 text-slate-400 text-sm">
        <p>暂无群聊记录</p>
        <p className="text-xs mt-1">将机器人加入群聊并 @机器人 后，群聊会自动出现在此处</p>
      </div>
    ) : (
      groups.map((group) => (
        <div
          key={group.id}
          className="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-700/50 rounded-lg border border-slate-200 dark:border-slate-600"
        >
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
              {group.groupName || group.groupId}
            </p>
            <p className="text-[11px] text-slate-400 truncate">{group.groupId}</p>
          </div>
          <div className="flex items-center gap-2 ml-3">
            {group.agentName ? (
              <>
                <span className="text-xs text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-900/20 px-2 py-0.5 rounded-full">
                  {group.agentName}
                </span>
                <button
                  onClick={async () => {
                    await unbindGroupAgent(channelId!, group.groupId);
                    loadGroups();
                  }}
                  className="text-xs text-red-500 hover:text-red-700"
                >
                  解绑
                </button>
              </>
            ) : (
              <select
                className="text-xs border border-slate-300 dark:border-slate-600 rounded px-2 py-1 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300"
                defaultValue=""
                onChange={async (e) => {
                  if (e.target.value) {
                    await bindGroupAgent(channelId!, group.groupId, e.target.value);
                    loadGroups();
                  }
                }}
              >
                <option value="" disabled>
                  绑定 Agent...
                </option>
                {agents.map((a) => (
                  <option key={a.name} value={a.name}>{a.name}</option>
                ))}
              </select>
            )}
          </div>
        </div>
      ))
    )}
  </div>
)}
```

注意：Agent 列表的获取方式取决于 `ChannelConfigModal` 中是否已有 `agents` state。查看该组件中 `defaultAgent` 选择的实现——如果已有 agent 列表数据，直接复用；如果没有，需要调用 agent 列表 API（如 `GET /api/agents`）。

- [ ] **步骤 4：确保配置表单只在 config tab 时显示**

将原有的配置表单包裹在 `{activeTab === "config" && (...)}` 中，或者当 `!channelId`（新建模式）时始终显示。

- [ ] **步骤 5：编译验证**

运行：`cd intellimate-web && npm run build`
预期：无报错

- [ ] **步骤 6：Commit**

```bash
git add intellimate-web/src/components/ChannelConfigModal.tsx
git commit -m "feat: add group chat management tab in channel config modal"
```

---

### 任务 15：后端测试同步更新

**文件：**
- 根据项目中已有的测试文件结构，同步更新受影响的测试

- [ ] **步骤 1：检查现有测试文件**

运行：`find intellimate-gateway/src/test -name "*.java" -type f | sort`
了解测试目录结构和已有测试。

- [ ] **步骤 2：如果存在 ChannelPipelineConfig 或 ChatInjectionService 的测试，更新构造函数参数**

由于多个类的构造函数签名发生了变化（新增参数），需要更新测试中 mock/构造这些类的代码。

- [ ] **步骤 3：编译并运行测试**

运行：`cd intellimate-gateway && mvn test`
预期：所有测试通过

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "test: update tests for new constructor parameters"
```

---

### 任务 16：集成验证

- [ ] **步骤 1：启动后端应用**

运行：`cd intellimate-gateway && mvn spring-boot:run`
预期：启动成功，Flyway V41 迁移通过，无报错

- [ ] **步骤 2：启动前端**

运行：`cd intellimate-web && npm run dev`
预期：启动成功

- [ ] **步骤 3：验证绑定码容错**

在钉钉中给机器人发送 `绑定 123456`（使用一个有效的绑定码）
预期：收到绑定成功/失败的明确回复

- [ ] **步骤 4：验证群聊记录**

在钉钉群中 @机器人 发一条消息
预期：调用 `GET /api/channels/dingtalk-stream/groups` 能看到该群

- [ ] **步骤 5：验证群聊 Agent 绑定**

调用 `PUT /api/channels/dingtalk-stream/groups/{groupId}/agent` 绑定 Agent
触发心跳/定时任务
预期：群里收到推送消息
