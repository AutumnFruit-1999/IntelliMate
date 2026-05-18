# Agent 心跳系统 - 详细实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Agent 拥有生命节律，能主动发起对话（问候、提醒、总结），并管理用户待办事项

**架构：** HeartbeatEngine 基于 Spring @Scheduled 定时检查，LifecycleStateMachine 计算 Agent 当前状态，满足触发条件时通过 AgentRuntime.dispatch 调用 LLM，通过 SessionRegistry 推送至 WebSocket 或缓存为离线消息。

**技术栈：** Spring Boot 3.4 / Spring WebFlux / R2DBC / Reactor / Flyway / React 19 / Zustand / TypeScript

---

## 文件清单

### 后端新增文件
| 文件路径 | 职责 |
|---------|------|
| `.../gateway/heartbeat/LifecycleState.java` | 生命状态枚举 + 计算逻辑 |
| `.../gateway/heartbeat/HeartbeatEngine.java` | 核心业务逻辑：触发判断、上下文构建、执行分发 |
| `.../gateway/heartbeat/HeartbeatScheduler.java` | Spring @Scheduled 定时器 |
| `.../gateway/heartbeat/HeartbeatContextBuilder.java` | 构建 LLM prompt 上下文 |
| `.../gateway/entity/HeartbeatConfigEntity.java` | 心跳配置实体 |
| `.../gateway/entity/HeartbeatLogEntity.java` | 心跳日志实体 |
| `.../gateway/entity/AgentTaskEntity.java` | 待办任务实体 |
| `.../gateway/entity/OfflineMessageEntity.java` | 离线消息实体 |
| `.../gateway/repository/HeartbeatConfigRepository.java` | |
| `.../gateway/repository/HeartbeatLogRepository.java` | |
| `.../gateway/repository/AgentTaskRepository.java` | |
| `.../gateway/repository/OfflineMessageRepository.java` | |
| `.../gateway/http/HeartbeatController.java` | 心跳配置 REST API |
| `.../gateway/http/TaskController.java` | 任务管理 REST API |
| `.../gateway/websocket/SessionRegistry.java` | WebSocket 会话注册表 |
| `.../resources/db/migration/V23__heartbeat_tables.sql` | 数据库迁移 |

### 后端修改文件
| 文件路径 | 修改内容 |
|---------|---------|
| `.../gateway/websocket/GatewayWebSocketHandler.java` | 注入 SessionRegistry，注册/注销会话 |

### 前端新增文件
| 文件路径 | 职责 |
|---------|------|
| `javaclaw-web/src/components/HeartbeatConfigPanel.tsx` | 心跳配置 UI |
| `javaclaw-web/src/components/TaskManager.tsx` | 任务管理 UI |
| `javaclaw-web/src/lib/heartbeatApi.ts` | 心跳相关 API 调用 |

### 前端修改文件
| 文件路径 | 修改内容 |
|---------|---------|
| `javaclaw-web/src/components/AgentConfigModal.tsx` | 添加「心跳」Tab |

---

## 任务 1：数据库迁移（V23）

**文件：** `javaclaw-gateway/src/main/resources/db/migration/V23__heartbeat_tables.sql`

- [ ] **步骤 1：创建迁移文件**

```sql
-- 心跳配置表
CREATE TABLE heartbeat_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    enabled TINYINT DEFAULT 0,
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai',
    wake_time VARCHAR(5) DEFAULT '06:00',
    sleep_time VARCHAR(5) DEFAULT '23:00',
    heartbeat_interval_minutes INT DEFAULT 60,
    personality_prompt TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 心跳执行日志
CREATE TABLE heartbeat_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL COMMENT 'SLEEPING/WAKING/ACTIVE/WINDING_DOWN',
    triggered_at DATETIME NOT NULL,
    prompt_used TEXT,
    response TEXT,
    delivered TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_time (agent_id, triggered_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 待办任务表
CREATE TABLE agent_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_id VARCHAR(100) DEFAULT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    due_at DATETIME DEFAULT NULL,
    remind_at DATETIME DEFAULT NULL,
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending/done/cancelled',
    priority INT DEFAULT 0 COMMENT '0=normal, 1=important, 2=urgent',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_status (agent_id, status),
    INDEX idx_remind (remind_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 离线消息队列
CREATE TABLE offline_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(20) DEFAULT 'heartbeat' COMMENT 'heartbeat/task_remind/greeting',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    delivered TINYINT DEFAULT 0,
    delivered_at DATETIME DEFAULT NULL,
    INDEX idx_agent_pending (agent_id, delivered, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **步骤 2：启动项目验证迁移**

```bash
mvn spring-boot:run -pl javaclaw-gateway -q
# 验证：
docker exec ba8cab9397fc mysql -uroot -p'NyD0+oFDoOB+9cdVtnMonWFf4ZkDpTTs' \
  -e "SELECT version, description, success FROM javaclaw.flyway_schema_history WHERE version='23';"
# 预期：version=23, success=1
```

- [ ] **步骤 3：Commit**

```bash
git add javaclaw-gateway/src/main/resources/db/migration/V23__heartbeat_tables.sql
git commit -m "feat(heartbeat): add database tables (config, log, task, offline_message)"
```

---

## 任务 2：实体类

**文件：**
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/HeartbeatConfigEntity.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/HeartbeatLogEntity.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/AgentTaskEntity.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/OfflineMessageEntity.java`

- [ ] **步骤 1：HeartbeatConfigEntity**

```java
package com.atm.javaclaw.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("heartbeat_config")
public class HeartbeatConfigEntity {
    @Id
    private Long id;
    private Long agentId;
    private Integer enabled;
    private String timezone;
    private String wakeTime;
    private String sleepTime;
    private Integer heartbeatIntervalMinutes;
    private String personalityPrompt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 所有字段的 getter/setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getWakeTime() { return wakeTime; }
    public void setWakeTime(String wakeTime) { this.wakeTime = wakeTime; }
    public String getSleepTime() { return sleepTime; }
    public void setSleepTime(String sleepTime) { this.sleepTime = sleepTime; }
    public Integer getHeartbeatIntervalMinutes() { return heartbeatIntervalMinutes; }
    public void setHeartbeatIntervalMinutes(Integer v) { this.heartbeatIntervalMinutes = v; }
    public String getPersonalityPrompt() { return personalityPrompt; }
    public void setPersonalityPrompt(String p) { this.personalityPrompt = p; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}
```

- [ ] **步骤 2：AgentTaskEntity**

```java
package com.atm.javaclaw.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("agent_task")
public class AgentTaskEntity {
    @Id
    private Long id;
    private Long agentId;
    private String userId;
    private String title;
    private String description;
    private LocalDateTime dueAt;
    private LocalDateTime remindAt;
    private String status;
    private Integer priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 所有字段的 getter/setter（同上模式）
}
```

- [ ] **步骤 3：HeartbeatLogEntity**

```java
package com.atm.javaclaw.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("heartbeat_log")
public class HeartbeatLogEntity {
    @Id
    private Long id;
    private Long agentId;
    private String state;
    private LocalDateTime triggeredAt;
    private String promptUsed;
    private String response;
    private Integer delivered;
    private LocalDateTime createdAt;

    // getter/setter
}
```

- [ ] **步骤 4：OfflineMessageEntity**

```java
package com.atm.javaclaw.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("offline_message")
public class OfflineMessageEntity {
    @Id
    private Long id;
    private Long agentId;
    private String content;
    private String messageType;
    private LocalDateTime createdAt;
    private Integer delivered;
    private LocalDateTime deliveredAt;

    // getter/setter
}
```

- [ ] **步骤 5：编译验证**

```bash
mvn compile -pl javaclaw-gateway -q
```

- [ ] **步骤 6：Commit**

```bash
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/HeartbeatConfigEntity.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/HeartbeatLogEntity.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/AgentTaskEntity.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/entity/OfflineMessageEntity.java
git commit -m "feat(heartbeat): add entity classes for heartbeat system"
```

---

## 任务 3：Repository 接口

**文件：**
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/HeartbeatConfigRepository.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/HeartbeatLogRepository.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/AgentTaskRepository.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/OfflineMessageRepository.java`

- [ ] **步骤 1：HeartbeatConfigRepository**

```java
package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.HeartbeatConfigEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface HeartbeatConfigRepository extends ReactiveCrudRepository<HeartbeatConfigEntity, Long> {
    Mono<HeartbeatConfigEntity> findByAgentId(Long agentId);
    Flux<HeartbeatConfigEntity> findAllByEnabled(Integer enabled);
}
```

- [ ] **步骤 2：HeartbeatLogRepository**

```java
package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.HeartbeatLogEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface HeartbeatLogRepository extends ReactiveCrudRepository<HeartbeatLogEntity, Long> {
    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId ORDER BY triggered_at DESC LIMIT 1")
    Mono<HeartbeatLogEntity> findLatestByAgentId(Long agentId);

    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId AND state = :state " +
           "AND DATE(triggered_at) = CURDATE() ORDER BY triggered_at DESC LIMIT 1")
    Mono<HeartbeatLogEntity> findTodayByAgentIdAndState(Long agentId, String state);

    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId ORDER BY triggered_at DESC LIMIT :limit")
    Flux<HeartbeatLogEntity> findRecentByAgentId(Long agentId, int limit);
}
```

- [ ] **步骤 3：AgentTaskRepository**

```java
package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.AgentTaskEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface AgentTaskRepository extends ReactiveCrudRepository<AgentTaskEntity, Long> {
    Flux<AgentTaskEntity> findByAgentIdAndStatus(Long agentId, String status);

    Flux<AgentTaskEntity> findByAgentId(Long agentId);

    @Query("SELECT * FROM agent_task WHERE agent_id = :agentId AND status = 'pending' " +
           "AND remind_at IS NOT NULL AND remind_at <= :now")
    Flux<AgentTaskEntity> findDueReminders(Long agentId, LocalDateTime now);

    @Query("SELECT * FROM agent_task WHERE agent_id = :agentId AND status = 'pending' " +
           "AND due_at IS NOT NULL AND due_at <= :deadline ORDER BY due_at")
    Flux<AgentTaskEntity> findUpcomingTasks(Long agentId, LocalDateTime deadline);
}
```

- [ ] **步骤 4：OfflineMessageRepository**

```java
package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.OfflineMessageEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OfflineMessageRepository extends ReactiveCrudRepository<OfflineMessageEntity, Long> {
    Flux<OfflineMessageEntity> findByAgentIdAndDeliveredOrderByCreatedAt(Long agentId, Integer delivered);

    @Modifying
    @Query("UPDATE offline_message SET delivered = 1, delivered_at = NOW() WHERE id = :id")
    Mono<Integer> markDelivered(Long id);

    @Query("SELECT COUNT(*) FROM offline_message WHERE agent_id = :agentId AND delivered = 0")
    Mono<Long> countPending(Long agentId);
}
```

- [ ] **步骤 5：编译验证**

```bash
mvn compile -pl javaclaw-gateway -q
```

- [ ] **步骤 6：Commit**

```bash
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/Heartbeat*.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/AgentTaskRepository.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/repository/OfflineMessageRepository.java
git commit -m "feat(heartbeat): add repository interfaces"
```

---

## 任务 4：LifecycleState + SessionRegistry

**文件：**
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/LifecycleState.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/websocket/SessionRegistry.java`
- 修改：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/websocket/GatewayWebSocketHandler.java`

- [ ] **步骤 1：LifecycleState 枚举**

```java
package com.atm.javaclaw.gateway.heartbeat;

import java.time.LocalTime;

public enum LifecycleState {
    SLEEPING,
    WAKING,
    ACTIVE,
    WINDING_DOWN;

    public static LifecycleState compute(LocalTime now, LocalTime wakeTime, LocalTime sleepTime) {
        LocalTime wakingEnd = wakeTime.plusHours(1);
        LocalTime windingStart = sleepTime.minusHours(2);

        if (isBetween(now, sleepTime, wakeTime)) return SLEEPING;
        if (isBetween(now, wakeTime, wakingEnd)) return WAKING;
        if (isBetween(now, windingStart, sleepTime)) return WINDING_DOWN;
        return ACTIVE;
    }

    private static boolean isBetween(LocalTime time, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        // 跨午夜（如 23:00 → 06:00）
        return !time.isBefore(start) || time.isBefore(end);
    }

    public String description() {
        return switch (this) {
            case SLEEPING -> "休眠中";
            case WAKING -> "刚醒来";
            case ACTIVE -> "活跃中";
            case WINDING_DOWN -> "准备休息";
        };
    }
}
```

- [ ] **步骤 2：SessionRegistry**

```java
package com.atm.javaclaw.gateway.websocket;

import com.atm.javaclaw.core.protocol.EventFrame;
import com.atm.javaclaw.core.protocol.GatewayFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final AtomicLong seqGenerator = new AtomicLong(0);

    // wsSessionId → outSink
    private final ConcurrentHashMap<String, Sinks.Many<GatewayFrame>> sessionSinks = new ConcurrentHashMap<>();
    // agentName → wsSessionId (最近活跃的连接)
    private final ConcurrentHashMap<String, String> agentSessions = new ConcurrentHashMap<>();

    public void register(String wsSessionId, Sinks.Many<GatewayFrame> sink) {
        sessionSinks.put(wsSessionId, sink);
        log.debug("Session registered: {}", wsSessionId);
    }

    public void bindAgent(String wsSessionId, String agentName) {
        agentSessions.put(agentName, wsSessionId);
        log.debug("Agent '{}' bound to session {}", agentName, wsSessionId);
    }

    public void unregister(String wsSessionId) {
        sessionSinks.remove(wsSessionId);
        agentSessions.entrySet().removeIf(e -> e.getValue().equals(wsSessionId));
        log.debug("Session unregistered: {}", wsSessionId);
    }

    public boolean isAgentOnline(String agentName) {
        String sid = agentSessions.get(agentName);
        return sid != null && sessionSinks.containsKey(sid);
    }

    public boolean pushToAgent(String agentName, String eventType, Map<String, Object> payload) {
        String sid = agentSessions.get(agentName);
        if (sid == null) return false;
        Sinks.Many<GatewayFrame> sink = sessionSinks.get(sid);
        if (sink == null) return false;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        return sink.tryEmitNext(frame).isSuccess();
    }
}
```

- [ ] **步骤 3：修改 GatewayWebSocketHandler**

在 `handle()` 方法中：
- 注入 `SessionRegistry`
- 连接时调用 `sessionRegistry.register(session.getId(), outSink)`
- 断开时调用 `sessionRegistry.unregister(session.getId())`
- 在第一条 `conversation.message` 时调用 `sessionRegistry.bindAgent(wsSessionId, agentName)`

- [ ] **步骤 4：编译验证**

- [ ] **步骤 5：Commit**

```bash
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/LifecycleState.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/websocket/SessionRegistry.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/websocket/GatewayWebSocketHandler.java
git commit -m "feat(heartbeat): add LifecycleState enum and SessionRegistry"
```

---

## 任务 5：HeartbeatEngine + HeartbeatScheduler

**文件：**
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/HeartbeatEngine.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/HeartbeatContextBuilder.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/HeartbeatScheduler.java`

- [ ] **步骤 1：HeartbeatContextBuilder**

```java
package com.atm.javaclaw.gateway.heartbeat;

import com.atm.javaclaw.gateway.entity.AgentTaskEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class HeartbeatContextBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String buildPrompt(String agentName, LifecycleState state,
                              String personalityPrompt, List<AgentTaskEntity> tasks,
                              LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ").append(agentName).append("，现在是 ")
          .append(now.format(DATETIME_FMT)).append("（").append(state.description()).append("）。\n\n");

        if (personalityPrompt != null && !personalityPrompt.isBlank()) {
            sb.append("你的性格设定：\n").append(personalityPrompt).append("\n\n");
        }

        sb.append("待办事项：\n");
        if (tasks.isEmpty()) {
            sb.append("- 暂无待办事项\n");
        } else {
            for (AgentTaskEntity task : tasks) {
                sb.append("- ").append(task.getTitle());
                if (task.getDueAt() != null) {
                    sb.append("（截止：").append(task.getDueAt().format(DATETIME_FMT)).append("）");
                }
                if (task.getPriority() != null && task.getPriority() > 0) {
                    sb.append(task.getPriority() == 2 ? " [紧急]" : " [重要]");
                }
                sb.append("\n");
            }
        }

        sb.append("\n根据当前情境，请决定是否需要对用户说些什么：\n");
        sb.append("- 如果是「刚醒来」状态：发送温暖的早安问候，提及今天的待办事项\n");
        sb.append("- 如果有到期/即将到期的任务：友好地提醒用户\n");
        sb.append("- 如果是「准备休息」状态：总结今天，提醒明天的事项\n");
        sb.append("- 如果觉得没有必要说话：仅回复 [SILENT]\n\n");
        sb.append("注意：保持简洁自然，像朋友一样聊天，不要过于正式或冗长（控制在 100 字以内）。");

        return sb.toString();
    }
}
```

- [ ] **步骤 2：HeartbeatEngine**

```java
package com.atm.javaclaw.gateway.heartbeat;

import com.atm.javaclaw.gateway.entity.*;
import com.atm.javaclaw.gateway.repository.*;
import com.atm.javaclaw.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;
import java.util.Map;

@Service
public class HeartbeatEngine {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatEngine.class);
    private static final String SILENT_MARKER = "[SILENT]";

    private final HeartbeatLogRepository logRepo;
    private final AgentTaskRepository taskRepo;
    private final OfflineMessageRepository offlineMsgRepo;
    private final SessionRegistry sessionRegistry;
    private final HeartbeatContextBuilder contextBuilder;
    // TODO: 注入 AgentRuntime（需要解决对话上下文问题）

    public HeartbeatEngine(HeartbeatLogRepository logRepo,
                           AgentTaskRepository taskRepo,
                           OfflineMessageRepository offlineMsgRepo,
                           SessionRegistry sessionRegistry,
                           HeartbeatContextBuilder contextBuilder) {
        this.logRepo = logRepo;
        this.taskRepo = taskRepo;
        this.offlineMsgRepo = offlineMsgRepo;
        this.sessionRegistry = sessionRegistry;
        this.contextBuilder = contextBuilder;
    }

    public Mono<Void> processHeartbeat(HeartbeatConfigEntity config) {
        ZoneId zone = ZoneId.of(config.getTimezone());
        LocalDateTime now = LocalDateTime.now(zone);
        LocalTime nowTime = now.toLocalTime();
        LocalTime wakeTime = LocalTime.parse(config.getWakeTime());
        LocalTime sleepTime = LocalTime.parse(config.getSleepTime());

        LifecycleState state = LifecycleState.compute(nowTime, wakeTime, sleepTime);

        if (state == LifecycleState.SLEEPING) {
            return Mono.empty();
        }

        return shouldTrigger(config, state)
                .flatMap(shouldFire -> {
                    if (!shouldFire) return Mono.empty();
                    return executeBeat(config, state, now);
                });
    }

    private Mono<Boolean> shouldTrigger(HeartbeatConfigEntity config, LifecycleState state) {
        if (state == LifecycleState.WAKING || state == LifecycleState.WINDING_DOWN) {
            // 状态转换只触发一次（检查今天是否已有同状态日志）
            return logRepo.findTodayByAgentIdAndState(config.getAgentId(), state.name())
                    .map(existing -> false)
                    .defaultIfEmpty(true);
        }

        // ACTIVE 状态：检查间隔 + 是否有到期任务
        return logRepo.findLatestByAgentId(config.getAgentId())
                .map(latest -> {
                    Duration elapsed = Duration.between(latest.getTriggeredAt(), LocalDateTime.now());
                    return elapsed.toMinutes() >= config.getHeartbeatIntervalMinutes();
                })
                .defaultIfEmpty(true)
                .flatMap(intervalOk -> {
                    if (!intervalOk) return Mono.just(false);
                    // 间隔到了，检查是否有到期任务
                    return taskRepo.findDueReminders(config.getAgentId(), LocalDateTime.now())
                            .hasElements();
                });
    }

    private Mono<Void> executeBeat(HeartbeatConfigEntity config, LifecycleState state, LocalDateTime now) {
        Long agentId = config.getAgentId();

        return taskRepo.findUpcomingTasks(agentId, now.plusHours(2))
                .collectList()
                .flatMap(tasks -> {
                    String prompt = contextBuilder.buildPrompt(
                            "Agent#" + agentId, state,
                            config.getPersonalityPrompt(), tasks, now);

                    // TODO: 调用 AgentRuntime.dispatch 获取 LLM 回复
                    // 暂时用占位逻辑，后续集成
                    String response = generatePlaceholderResponse(state, tasks);

                    if (SILENT_MARKER.equals(response.trim())) {
                        log.debug("Heartbeat for agent {} decided to stay silent", agentId);
                        return saveLog(config, state, prompt, response).then();
                    }

                    return saveLog(config, state, prompt, response)
                            .then(deliver(config, response));
                });
    }

    private String generatePlaceholderResponse(LifecycleState state, List<AgentTaskEntity> tasks) {
        // 占位实现，后续替换为 LLM 调用
        return switch (state) {
            case WAKING -> "早上好！新的一天开始了。" +
                    (tasks.isEmpty() ? "" : "今天有 " + tasks.size() + " 件待办事项。");
            case WINDING_DOWN -> "晚上好！今天辛苦了，早点休息。";
            case ACTIVE -> tasks.isEmpty() ? SILENT_MARKER :
                    "提醒：你有 " + tasks.size() + " 个任务即将到期。";
            default -> SILENT_MARKER;
        };
    }

    private Mono<HeartbeatLogEntity> saveLog(HeartbeatConfigEntity config,
                                              LifecycleState state, String prompt, String response) {
        HeartbeatLogEntity logEntry = new HeartbeatLogEntity();
        logEntry.setAgentId(config.getAgentId());
        logEntry.setState(state.name());
        logEntry.setTriggeredAt(LocalDateTime.now());
        logEntry.setPromptUsed(prompt);
        logEntry.setResponse(response);
        logEntry.setDelivered(0);
        logEntry.setCreatedAt(LocalDateTime.now());
        return logRepo.save(logEntry);
    }

    private Mono<Void> deliver(HeartbeatConfigEntity config, String response) {
        String agentName = "Agent#" + config.getAgentId();

        boolean delivered = sessionRegistry.pushToAgent(agentName, "heartbeat.message",
                Map.of("content", response, "agentId", config.getAgentId()));

        if (delivered) {
            log.info("Heartbeat message delivered to agent {} via WebSocket", config.getAgentId());
            return Mono.empty();
        }

        // 离线缓存
        log.info("Agent {} offline, caching heartbeat message", config.getAgentId());
        OfflineMessageEntity msg = new OfflineMessageEntity();
        msg.setAgentId(config.getAgentId());
        msg.setContent(response);
        msg.setMessageType("heartbeat");
        msg.setCreatedAt(LocalDateTime.now());
        msg.setDelivered(0);
        return offlineMsgRepo.save(msg).then();
    }
}
```

- [ ] **步骤 3：HeartbeatScheduler**

```java
package com.atm.javaclaw.gateway.heartbeat;

import com.atm.javaclaw.gateway.repository.HeartbeatConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final HeartbeatEngine engine;
    private final HeartbeatConfigRepository configRepo;

    public HeartbeatScheduler(HeartbeatEngine engine, HeartbeatConfigRepository configRepo) {
        this.engine = engine;
        this.configRepo = configRepo;
    }

    @Scheduled(fixedRate = 60_000)
    public void tick() {
        configRepo.findAllByEnabled(1)
                .flatMap(config -> engine.processHeartbeat(config)
                        .onErrorResume(e -> {
                            log.error("Heartbeat failed for agent {}: {}",
                                    config.getAgentId(), e.getMessage());
                            return reactor.core.publisher.Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
```

- [ ] **步骤 4：编译验证**

```bash
mvn compile -pl javaclaw-gateway -q
```

- [ ] **步骤 5：Commit**

```bash
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/heartbeat/
git commit -m "feat(heartbeat): add HeartbeatEngine, ContextBuilder, and Scheduler"
```

---

## 任务 6：HTTP API

**文件：**
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/http/HeartbeatController.java`
- 创建：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/http/TaskController.java`

- [ ] **步骤 1：HeartbeatController**

```java
package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.entity.HeartbeatConfigEntity;
import com.atm.javaclaw.gateway.heartbeat.LifecycleState;
import com.atm.javaclaw.gateway.repository.HeartbeatConfigRepository;
import com.atm.javaclaw.gateway.repository.HeartbeatLogRepository;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/heartbeat")
public class HeartbeatController {

    private final HeartbeatConfigRepository configRepo;
    private final HeartbeatLogRepository logRepo;

    public HeartbeatController(HeartbeatConfigRepository configRepo,
                               HeartbeatLogRepository logRepo) {
        this.configRepo = configRepo;
        this.logRepo = logRepo;
    }

    @GetMapping("/{agentId}")
    public Mono<Map<String, Object>> getConfig(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .map(this::toDto)
                .defaultIfEmpty(defaultConfig(agentId));
    }

    @PutMapping("/{agentId}")
    public Mono<Map<String, Object>> updateConfig(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> body) {
        return configRepo.findByAgentId(agentId)
                .defaultIfEmpty(newConfig(agentId))
                .flatMap(config -> {
                    if (body.containsKey("enabled")) config.setEnabled((Integer) body.get("enabled"));
                    if (body.containsKey("timezone")) config.setTimezone((String) body.get("timezone"));
                    if (body.containsKey("wakeTime")) config.setWakeTime((String) body.get("wakeTime"));
                    if (body.containsKey("sleepTime")) config.setSleepTime((String) body.get("sleepTime"));
                    if (body.containsKey("heartbeatIntervalMinutes"))
                        config.setHeartbeatIntervalMinutes((Integer) body.get("heartbeatIntervalMinutes"));
                    if (body.containsKey("personalityPrompt"))
                        config.setPersonalityPrompt((String) body.get("personalityPrompt"));
                    config.setUpdatedAt(LocalDateTime.now());
                    return configRepo.save(config);
                })
                .map(this::toDto);
    }

    @GetMapping("/{agentId}/state")
    public Mono<Map<String, Object>> getState(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .map(config -> {
                    ZoneId zone = ZoneId.of(config.getTimezone());
                    LocalTime now = LocalTime.now(zone);
                    LocalTime wake = LocalTime.parse(config.getWakeTime());
                    LocalTime sleep = LocalTime.parse(config.getSleepTime());
                    LifecycleState state = LifecycleState.compute(now, wake, sleep);
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("currentState", state.name());
                    dto.put("stateDescription", state.description());
                    dto.put("currentTime", now.toString());
                    return dto;
                })
                .defaultIfEmpty(Map.of("currentState", "UNCONFIGURED"));
    }

    @GetMapping("/{agentId}/logs")
    public Mono<Object> getLogs(@PathVariable Long agentId,
                                @RequestParam(defaultValue = "20") int limit) {
        return logRepo.findRecentByAgentId(agentId, limit).collectList().map(l -> l);
    }

    private Map<String, Object> toDto(HeartbeatConfigEntity e) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", e.getId());
        dto.put("agentId", e.getAgentId());
        dto.put("enabled", e.getEnabled());
        dto.put("timezone", e.getTimezone());
        dto.put("wakeTime", e.getWakeTime());
        dto.put("sleepTime", e.getSleepTime());
        dto.put("heartbeatIntervalMinutes", e.getHeartbeatIntervalMinutes());
        dto.put("personalityPrompt", e.getPersonalityPrompt());
        return dto;
    }

    private Map<String, Object> defaultConfig(Long agentId) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("agentId", agentId);
        dto.put("enabled", 0);
        dto.put("timezone", "Asia/Shanghai");
        dto.put("wakeTime", "06:00");
        dto.put("sleepTime", "23:00");
        dto.put("heartbeatIntervalMinutes", 60);
        dto.put("personalityPrompt", "");
        return dto;
    }

    private HeartbeatConfigEntity newConfig(Long agentId) {
        HeartbeatConfigEntity e = new HeartbeatConfigEntity();
        e.setAgentId(agentId);
        e.setEnabled(0);
        e.setTimezone("Asia/Shanghai");
        e.setWakeTime("06:00");
        e.setSleepTime("23:00");
        e.setHeartbeatIntervalMinutes(60);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }
}
```

- [ ] **步骤 2：TaskController**

```java
package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.entity.AgentTaskEntity;
import com.atm.javaclaw.gateway.repository.AgentTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AgentTaskRepository taskRepo;

    public TaskController(AgentTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    @GetMapping("/{agentId}")
    public Mono<List<AgentTaskEntity>> listTasks(@PathVariable Long agentId,
                                                  @RequestParam(required = false) String status) {
        if (status != null) {
            return taskRepo.findByAgentIdAndStatus(agentId, status).collectList();
        }
        return taskRepo.findByAgentId(agentId).collectList();
    }

    @PostMapping("/{agentId}")
    public Mono<AgentTaskEntity> createTask(@PathVariable Long agentId,
                                             @RequestBody Map<String, Object> body) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setAgentId(agentId);
        task.setTitle((String) body.get("title"));
        task.setDescription((String) body.get("description"));
        if (body.get("dueAt") != null) task.setDueAt(LocalDateTime.parse((String) body.get("dueAt")));
        if (body.get("remindAt") != null) task.setRemindAt(LocalDateTime.parse((String) body.get("remindAt")));
        task.setStatus("pending");
        task.setPriority(body.get("priority") != null ? (Integer) body.get("priority") : 0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return taskRepo.save(task);
    }

    @PutMapping("/{agentId}/{taskId}")
    public Mono<AgentTaskEntity> updateTask(@PathVariable Long agentId,
                                             @PathVariable Long taskId,
                                             @RequestBody Map<String, Object> body) {
        return taskRepo.findById(taskId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(task -> {
                    if (body.containsKey("title")) task.setTitle((String) body.get("title"));
                    if (body.containsKey("description")) task.setDescription((String) body.get("description"));
                    if (body.containsKey("status")) task.setStatus((String) body.get("status"));
                    if (body.containsKey("priority")) task.setPriority((Integer) body.get("priority"));
                    if (body.containsKey("dueAt"))
                        task.setDueAt(body.get("dueAt") != null ? LocalDateTime.parse((String) body.get("dueAt")) : null);
                    if (body.containsKey("remindAt"))
                        task.setRemindAt(body.get("remindAt") != null ? LocalDateTime.parse((String) body.get("remindAt")) : null);
                    task.setUpdatedAt(LocalDateTime.now());
                    return taskRepo.save(task);
                });
    }

    @DeleteMapping("/{agentId}/{taskId}")
    public Mono<Map<String, Object>> deleteTask(@PathVariable Long agentId,
                                                 @PathVariable Long taskId) {
        return taskRepo.deleteById(taskId)
                .thenReturn(Map.<String, Object>of("success", true));
    }
}
```

- [ ] **步骤 3：编译验证**

```bash
mvn compile -pl javaclaw-gateway -q
```

- [ ] **步骤 4：Commit**

```bash
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/http/HeartbeatController.java
git add javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/http/TaskController.java
git commit -m "feat(heartbeat): add REST API controllers for heartbeat config and task management"
```

---

## 任务 7：前端 UI

**文件：**
- 创建：`javaclaw-web/src/lib/heartbeatApi.ts`
- 创建：`javaclaw-web/src/components/HeartbeatConfigPanel.tsx`
- 创建：`javaclaw-web/src/components/TaskManager.tsx`
- 修改：`javaclaw-web/src/components/AgentConfigModal.tsx`

- [ ] **步骤 1：heartbeatApi.ts - API 调用函数**
- [ ] **步骤 2：HeartbeatConfigPanel.tsx - 配置面板**（开关、时间、间隔、prompt）
- [ ] **步骤 3：TaskManager.tsx - 任务管理**（列表、新建、完成、删除）
- [ ] **步骤 4：AgentConfigModal 添加「心跳」Tab**
- [ ] **步骤 5：TypeScript 类型检查**

```bash
cd javaclaw-web && npx tsc --noEmit
```

- [ ] **步骤 6：Commit**

```bash
git add javaclaw-web/src/lib/heartbeatApi.ts
git add javaclaw-web/src/components/HeartbeatConfigPanel.tsx
git add javaclaw-web/src/components/TaskManager.tsx
git add javaclaw-web/src/components/AgentConfigModal.tsx
git commit -m "feat(heartbeat): add frontend UI for heartbeat config and task management"
```

---

## 任务 8：集成测试

- [ ] **步骤 1：为测试 Agent 创建心跳配置**

```bash
curl -X PUT http://localhost:3007/api/heartbeat/1 \
  -H "Content-Type: application/json" \
  -d '{"enabled": 1, "heartbeatIntervalMinutes": 1, "personalityPrompt": "你是一个温暖的AI伴侣"}'
```

- [ ] **步骤 2：创建测试任务**

```bash
curl -X POST http://localhost:3007/api/tasks/1 \
  -H "Content-Type: application/json" \
  -d '{"title": "测试提醒", "remindAt": "2026-05-18T12:00:00"}'
```

- [ ] **步骤 3：观察心跳日志**

```bash
curl http://localhost:3007/api/heartbeat/1/logs
```

- [ ] **步骤 4：验证 WebSocket 推送**

打开前端页面，确认收到心跳消息

- [ ] **步骤 5：Final commit**

```bash
git add -A
git commit -m "feat(heartbeat): complete heartbeat system integration"
```
