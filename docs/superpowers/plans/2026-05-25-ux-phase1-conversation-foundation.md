# UX Phase 1: 对话基础重建 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 IntelliMate 的对话可信赖——刷新不丢消息、能通过 /clear 开新话题、能重新生成回复、有 URL 路由。

**架构：** 后端新增 REST Controller 暴露消息历史和会话归档能力（复用现有 session + transcript_message 表，新增 status/title 字段）。前端引入 react-router-dom 替换 ViewMode 状态驱动，在 WebSocket 连接建立后自动加载消息历史。

**技术栈：** Java 21 / Spring WebFlux / R2DBC / Flyway / React 19 / TypeScript / Zustand / react-router-dom v7

**规格文档：** `docs/superpowers/specs/2026-05-25-user-experience-optimization-design.md` Phase 1 部分

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-gateway/src/main/resources/db/migration/V31__session_status_title.sql` | 数据库 schema 变更 |
| `intellimate-gateway/src/main/java/.../http/SessionHistoryController.java` | 对话历史 REST API |
| `intellimate-web/src/lib/sessionApi.ts` | 前端会话历史 API 客户端 |
| `intellimate-web/src/components/HistoryPage.tsx` | 历史界面（对话 + 计划整合） |
| `intellimate-web/src/components/ArchivedChatView.tsx` | 归档对话只读视图 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `intellimate-gateway/.../entity/SessionEntity.java` | 新增 status、title 字段 |
| `intellimate-gateway/.../repository/SessionRepository.java` | 新增查询方法 |
| `intellimate-gateway/.../session/SessionManager.java` | 新增 archiveAndCreate 方法 |
| `intellimate-gateway/.../session/SessionManagerImpl.java` | 实现归档逻辑 |
| `intellimate-gateway/.../pipeline/MessagePipeline.java` | 处理 regenerate 标志 |
| `intellimate-web/package.json` | 添加 react-router-dom 依赖 |
| `intellimate-web/src/App.tsx` | 路由替换 ViewMode |
| `intellimate-web/src/hooks/useWebSocket.ts` | 连接后加载历史 |
| `intellimate-web/src/stores/chatStore.ts` | 新增 loadHistory / prependHistory 方法 |
| `intellimate-web/src/components/MessageBubble.tsx` | 添加时间戳显示 |
| `intellimate-web/src/components/ComposeArea.tsx` | regenerate 按钮支持 |
| `intellimate-web/src/components/MessageList.tsx` | 向上翻页加载 |
| `intellimate-web/src/components/Sidebar.tsx` | 导航改为 router Link |
| `intellimate-web/src/components/CommandPopup.tsx` | /clear 命令处理 |

---

## 任务 1：数据库 Schema 变更

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V31__session_status_title.sql`

- [ ] **步骤 1：编写 Flyway 迁移脚本**

```sql
-- V31__session_status_title.sql
ALTER TABLE `session` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'active' AFTER `agent_name`;
ALTER TABLE `session` ADD COLUMN `title` VARCHAR(100) NULL AFTER `status`;
CREATE INDEX idx_session_agent_status ON `session` (`agent_name`, `status`, `deleted`);
```

- [ ] **步骤 2：更新 SessionEntity.java 添加新字段**

在 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/SessionEntity.java` 中添加：

```java
private String status;
private String title;

public String getStatus() { return status; }
public void setStatus(String status) { this.status = status; }
public String getTitle() { return title; }
public void setTitle(String title) { this.title = title; }
```

- [ ] **步骤 3：启动后端验证迁移成功**

运行：`cd intellimate-gateway && mvn spring-boot:run -Dspring-boot.run.profiles=dev`

预期：应用启动无报错，Flyway 执行 V31 迁移成功（日志中可见 `Successfully applied 1 migration`）。

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V31__session_status_title.sql
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/SessionEntity.java
git commit -m "feat(session): add status and title fields to session table"
```

---

## 任务 2：后端 SessionRepository 扩展

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/SessionRepository.java`

- [ ] **步骤 1：添加新查询方法**

```java
@Query("SELECT * FROM session WHERE agent_name = :agentName AND status = 'active' AND deleted = 0 AND channel_id = 'webchat' LIMIT 1")
Mono<SessionEntity> findActiveByAgentName(String agentName);

@Query("SELECT * FROM session WHERE agent_name = :agentName AND status = 'archived' AND deleted = 0 AND channel_id = 'webchat' ORDER BY last_active_at DESC LIMIT :limit OFFSET :offset")
Flux<SessionEntity> findArchivedByAgentName(String agentName, int limit, int offset);

@Query("SELECT COUNT(*) FROM session WHERE agent_name = :agentName AND status = 'archived' AND deleted = 0 AND channel_id = 'webchat'")
Mono<Long> countArchivedByAgentName(String agentName);

@Modifying
@Query("UPDATE session SET status = 'archived', title = :title WHERE id = :id")
Mono<Long> archiveSession(Long id, String title);
```

- [ ] **步骤 2：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/SessionRepository.java
git commit -m "feat(session): add repository methods for active/archived session queries"
```

---

## 任务 3：后端 SessionManager 归档逻辑

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManager.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java`

- [ ] **步骤 1：在 SessionManager 接口添加方法**

```java
Mono<SessionEntity> findActiveSession(String agentName);

Mono<SessionEntity> archiveAndCreateNew(String agentName);

Flux<SessionEntity> getArchivedSessions(String agentName, int limit, int offset);

Mono<Long> countArchivedSessions(String agentName);
```

- [ ] **步骤 2：在 SessionManagerImpl 中实现**

```java
@Override
public Mono<SessionEntity> findActiveSession(String agentName) {
    return sessionRepository.findActiveByAgentName(agentName)
            .switchIfEmpty(Mono.defer(() -> {
                // 兼容旧数据：查找没有 status 的旧 session
                SessionKey key = new SessionKey("webchat", "dm", agentName);
                return sessionRepository.findBySessionKey("webchat", "dm", agentName)
                        .flatMap(s -> {
                            s.setStatus("active");
                            return sessionRepository.save(s);
                        });
            }));
}

@Override
public Mono<SessionEntity> archiveAndCreateNew(String agentName) {
    return findActiveSession(agentName)
            .flatMap(activeSession -> {
                // 获取第一条用户消息作为标题
                return transcriptRepository.findRecentBySessionIdNoPlanAfter(
                        activeSession.getId(),
                        activeSession.getCreatedAt(),
                        50
                )
                .filter(m -> "user".equals(m.getRole()))
                .next()
                .map(firstMsg -> {
                    String content = firstMsg.getContent();
                    return content.length() > 30 ? content.substring(0, 30) : content;
                })
                .defaultIfEmpty("空对话")
                .flatMap(title -> sessionRepository.archiveSession(activeSession.getId(), title))
                .then(Mono.defer(() -> {
                    // 创建新活跃 session
                    SessionEntity newSession = new SessionEntity();
                    newSession.setChannelId("webchat");
                    newSession.setContextType("dm");
                    newSession.setContextId(agentName);
                    newSession.setAgentName(agentName);
                    newSession.setStatus("active");
                    newSession.setLastActiveAt(LocalDateTime.now());
                    newSession.setCreatedAt(LocalDateTime.now());
                    newSession.setDeleted(0);
                    return sessionRepository.save(newSession);
                }));
            })
            .switchIfEmpty(Mono.defer(() -> {
                // 没有活跃 session，直接创建一个
                SessionEntity newSession = new SessionEntity();
                newSession.setChannelId("webchat");
                newSession.setContextType("dm");
                newSession.setContextId(agentName);
                newSession.setAgentName(agentName);
                newSession.setStatus("active");
                newSession.setLastActiveAt(LocalDateTime.now());
                newSession.setCreatedAt(LocalDateTime.now());
                newSession.setDeleted(0);
                return sessionRepository.save(newSession);
            }));
}

@Override
public Flux<SessionEntity> getArchivedSessions(String agentName, int limit, int offset) {
    return sessionRepository.findArchivedByAgentName(agentName, limit, offset);
}

@Override
public Mono<Long> countArchivedSessions(String agentName) {
    return sessionRepository.countArchivedByAgentName(agentName);
}
```

- [ ] **步骤 3：启动后端验证编译通过**

运行：`cd intellimate-gateway && mvn compile`

预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManager.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java
git commit -m "feat(session): implement archive-and-create-new session lifecycle"
```

---

## 任务 4：后端 SessionHistoryController

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SessionHistoryController.java`

- [ ] **步骤 1：创建 Controller**

```java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
public class SessionHistoryController {

    private final SessionManager sessionManager;
    private final TranscriptMessageRepository transcriptRepository;

    public SessionHistoryController(SessionManager sessionManager,
                                     TranscriptMessageRepository transcriptRepository) {
        this.sessionManager = sessionManager;
        this.transcriptRepository = transcriptRepository;
    }

    @GetMapping("/{agentName}/messages")
    public Mono<Map<String, Object>> getActiveMessages(
            @PathVariable String agentName,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long before) {
        return sessionManager.findActiveSession(agentName)
                .flatMap(session -> {
                    var query = before != null
                            ? transcriptRepository.findRecentBySessionIdBeforeId(session.getId(), before, limit)
                            : transcriptRepository.findRecentBySessionId(session.getId(), limit);
                    return query.collectList().map(messages -> {
                        messages.sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
                        List<Map<String, Object>> dtos = messages.stream()
                                .map(this::toMessageDto)
                                .collect(Collectors.toList());
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("messages", dtos);
                        result.put("hasMore", messages.size() >= limit);
                        return result;
                    });
                })
                .defaultIfEmpty(Map.of("messages", List.of(), "hasMore", false));
    }

    @PostMapping("/{agentName}/clear")
    public Mono<Map<String, Object>> clearSession(@PathVariable String agentName) {
        return sessionManager.archiveAndCreateNew(agentName)
                .map(newSession -> Map.<String, Object>of(
                        "success", true,
                        "newSessionId", newSession.getId()
                ));
    }

    @GetMapping("/{agentName}/archived")
    public Mono<Map<String, Object>> getArchivedSessions(
            @PathVariable String agentName,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return sessionManager.getArchivedSessions(agentName, limit, offset)
                .map(this::toSessionSummaryDto)
                .collectList()
                .zipWith(sessionManager.countArchivedSessions(agentName))
                .map(tuple -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("sessions", tuple.getT1());
                    result.put("total", tuple.getT2());
                    result.put("hasMore", offset + limit < tuple.getT2());
                    return result;
                });
    }

    @GetMapping("/by-id/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        return transcriptRepository.findRecentBySessionId(sessionId, limit)
                .collectList()
                .map(messages -> {
                    messages.sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
                    List<Map<String, Object>> dtos = messages.stream()
                            .map(this::toMessageDto)
                            .collect(Collectors.toList());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("messages", dtos);
                    result.put("hasMore", messages.size() >= limit);
                    return result;
                });
    }

    private Map<String, Object> toMessageDto(TranscriptMessageEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("role", entity.getRole());
        dto.put("content", entity.getContent());
        dto.put("createdAt", entity.getCreatedAt().toString());
        dto.put("toolName", entity.getToolName());
        if (entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()) {
            dto.put("metadata", entity.getMetadataJson());
        }
        return dto;
    }

    private Map<String, Object> toSessionSummaryDto(SessionEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("title", entity.getTitle());
        dto.put("agentName", entity.getAgentName());
        dto.put("lastActiveAt", entity.getLastActiveAt().toString());
        dto.put("createdAt", entity.getCreatedAt().toString());
        return dto;
    }
}
```

- [ ] **步骤 2：在 TranscriptMessageRepository 中添加 `findRecentBySessionIdBeforeId` 方法**

```java
@Query("SELECT * FROM transcript_message WHERE session_id = :sessionId AND id < :beforeId ORDER BY created_at DESC LIMIT :limit")
Flux<TranscriptMessageEntity> findRecentBySessionIdBeforeId(Long sessionId, Long beforeId, int limit);
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile`

预期：BUILD SUCCESS

- [ ] **步骤 4：启动后端，用 curl 测试 API**

运行：`mvn spring-boot:run -pl intellimate-gateway`

测试：
```bash
curl http://localhost:3007/api/sessions/default/messages
curl http://localhost:3007/api/sessions/default/archived
```

预期：返回 JSON 格式的消息列表或空对象。

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SessionHistoryController.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/TranscriptMessageRepository.java
git commit -m "feat(api): add session history REST endpoints for messages and archiving"
```

---

## 任务 5：前端 Session API 客户端

**文件：**
- 创建：`intellimate-web/src/lib/sessionApi.ts`

- [ ] **步骤 1：创建 API 客户端模块**

```typescript
import { apiFetch } from "./httpClient";

export interface HistoryMessage {
  id: number;
  role: "user" | "assistant" | "tool";
  content: string;
  createdAt: string;
  toolName?: string;
  metadata?: string;
}

export interface MessagesResponse {
  messages: HistoryMessage[];
  hasMore: boolean;
}

export interface ArchivedSession {
  id: number;
  title: string;
  agentName: string;
  lastActiveAt: string;
  createdAt: string;
}

export interface ArchivedSessionsResponse {
  sessions: ArchivedSession[];
  total: number;
  hasMore: boolean;
}

export function fetchActiveMessages(
  agentName: string,
  limit = 50,
  before?: number,
): Promise<MessagesResponse> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (before != null) params.set("before", String(before));
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/messages?${params}`);
}

export function clearSession(agentName: string): Promise<{ success: boolean; newSessionId: number }> {
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/clear`, { method: "POST" });
}

export function fetchArchivedSessions(
  agentName: string,
  limit = 20,
  offset = 0,
): Promise<ArchivedSessionsResponse> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/archived?${params}`);
}

export function fetchSessionMessages(
  sessionId: number,
  limit = 100,
): Promise<MessagesResponse> {
  return apiFetch(`/api/sessions/by-id/${sessionId}/messages?limit=${limit}`);
}
```

- [ ] **步骤 2：Commit**

```bash
git add intellimate-web/src/lib/sessionApi.ts
git commit -m "feat(web): add session history API client"
```

---

## 任务 6：前端 ChatStore 扩展——历史加载

**文件：**
- 修改：`intellimate-web/src/stores/chatStore.ts`

- [ ] **步骤 1：在 ChatState 接口中添加方法声明**

在 `ChatState` 接口中添加：

```typescript
historyLoaded: boolean;
historyHasMore: boolean;
loadingHistory: boolean;

loadHistoryFromServer: (agentName: string) => Promise<void>;
prependHistory: (messages: ChatMessage[]) => void;
loadMoreHistory: (agentName: string) => Promise<void>;
setHistoryLoaded: (loaded: boolean) => void;
```

- [ ] **步骤 2：在 store 的 create 中实现**

```typescript
historyLoaded: false,
historyHasMore: false,
loadingHistory: false,

loadHistoryFromServer: async (agentName: string) => {
  const { fetchActiveMessages, type HistoryMessage } = await import("../lib/sessionApi");
  set({ loadingHistory: true });
  try {
    const resp = await fetchActiveMessages(agentName, 50);
    const messages: ChatMessage[] = resp.messages
      .filter((m) => m.role === "user" || m.role === "assistant")
      .map((m) => ({
        id: `hist-${m.id}`,
        role: m.role as "user" | "assistant",
        content: m.content ?? "",
        streaming: false,
        timestamp: new Date(m.createdAt).getTime(),
        toolCalls: undefined,
      }));
    const current = get().currentAgent;
    if (current === agentName) {
      set((state) => {
        const existing = state.messagesByAgent[agentName] ?? [];
        // 只在当前没有消息时加载（避免和实时消息重复）
        if (existing.length === 0) {
          return {
            messagesByAgent: { ...state.messagesByAgent, [agentName]: messages },
            messages: messages,
            historyLoaded: true,
            historyHasMore: resp.hasMore,
            loadingHistory: false,
          };
        }
        return { historyLoaded: true, historyHasMore: resp.hasMore, loadingHistory: false };
      });
    }
  } catch (e) {
    console.error("[chatStore] Failed to load history:", e);
    set({ loadingHistory: false });
  }
},

prependHistory: (messages: ChatMessage[]) => {
  const agent = get().currentAgent;
  set((state) => {
    const existing = state.messagesByAgent[agent] ?? [];
    const merged = [...messages, ...existing];
    return {
      messagesByAgent: { ...state.messagesByAgent, [agent]: merged },
      messages: merged,
    };
  });
},

loadMoreHistory: async (agentName: string) => {
  const { fetchActiveMessages } = await import("../lib/sessionApi");
  const currentMessages = get().messagesByAgent[agentName] ?? [];
  if (currentMessages.length === 0) return;
  const firstMsg = currentMessages[0];
  const beforeId = firstMsg.id.startsWith("hist-")
    ? parseInt(firstMsg.id.replace("hist-", ""), 10)
    : undefined;
  if (!beforeId) return;
  set({ loadingHistory: true });
  try {
    const resp = await fetchActiveMessages(agentName, 50, beforeId);
    const messages: ChatMessage[] = resp.messages
      .filter((m) => m.role === "user" || m.role === "assistant")
      .map((m) => ({
        id: `hist-${m.id}`,
        role: m.role as "user" | "assistant",
        content: m.content ?? "",
        streaming: false,
        timestamp: new Date(m.createdAt).getTime(),
        toolCalls: undefined,
      }));
    get().prependHistory(messages);
    set({ historyHasMore: resp.hasMore, loadingHistory: false });
  } catch (e) {
    console.error("[chatStore] Failed to load more history:", e);
    set({ loadingHistory: false });
  }
},

setHistoryLoaded: (loaded: boolean) => set({ historyLoaded: loaded }),
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-web && npx tsc --noEmit`

预期：无类型错误

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/stores/chatStore.ts
git commit -m "feat(web): add history loading methods to chatStore"
```

---

## 任务 7：前端 useWebSocket 连接后加载历史

**文件：**
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`

- [ ] **步骤 1：在 `session.welcome` 处理中添加历史加载**

在 `case "session.welcome"` 分支中，`agent.bind` 发送后添加：

```typescript
// 加载消息历史
const chatState = useChatStore.getState();
if (currentAgentName && !chatState.historyLoaded) {
  chatState.loadHistoryFromServer(currentAgentName);
}
```

- [ ] **步骤 2：在切换 Agent 时重置 historyLoaded 并加载新 Agent 的历史**

找到 `useWebSocket` hook 中处理 agent 切换的逻辑（`agent.bind` 发送的地方），在切换时：

```typescript
// 在 sendMessage 或 agent 切换处
// 当 activeAgent 变化时（通过 agentStore），重新加载历史
```

注意：实际的 agent 切换已在 `App.tsx` 的 `handleSelectAgent` 中处理，此处需确保 `setCurrentAgent` 时重置 `historyLoaded` 状态。在 chatStore 的 `setCurrentAgent` 方法中添加：

```typescript
setCurrentAgent: (agent: string) => {
  set((state) => ({
    currentAgent: agent,
    messages: state.messagesByAgent[agent] ?? [],
    historyLoaded: false, // 切换 agent 时重置，触发重新加载
  }));
},
```

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/hooks/useWebSocket.ts
git add intellimate-web/src/stores/chatStore.ts
git commit -m "feat(web): load message history on WebSocket connect and agent switch"
```

---

## 任务 8：前端消息时间戳显示

**文件：**
- 修改：`intellimate-web/src/components/MessageBubble.tsx`

- [ ] **步骤 1：添加时间格式化工具函数**

在 `MessageBubble.tsx` 文件顶部（组件外部）添加：

```typescript
function formatMessageTime(timestamp: number): string {
  const date = new Date(timestamp);
  const now = new Date();
  const hours = date.getHours().toString().padStart(2, "0");
  const minutes = date.getMinutes().toString().padStart(2, "0");
  const time = `${hours}:${minutes}`;

  if (
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  ) {
    return time;
  }
  const month = (date.getMonth() + 1).toString().padStart(2, "0");
  const day = date.getDate().toString().padStart(2, "0");
  if (date.getFullYear() === now.getFullYear()) {
    return `${month}-${day} ${time}`;
  }
  return `${date.getFullYear()}-${month}-${day} ${time}`;
}
```

- [ ] **步骤 2：在 MessageBubble 组件的渲染中添加时间戳**

在消息气泡容器 `<div className={`flex gap-3 my-4 ...`}>` 内部，avatar 和内容之间，内容 div 的第一行添加：

```tsx
<div className="max-w-[75%]">
  {message.timestamp > 0 && (
    <div className={`text-[10px] text-slate-400 dark:text-slate-500 mb-0.5 ${isUser ? "text-right" : ""}`}>
      {formatMessageTime(message.timestamp)}
    </div>
  )}
  {/* ... 原有内容 ... */}
</div>
```

- [ ] **步骤 3：验证开发服务器中显示正常**

运行：`cd intellimate-web && npm run dev`

打开浏览器访问 `http://localhost:5173`，发送消息后确认消息气泡上方显示时间。

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/components/MessageBubble.tsx
git commit -m "feat(web): display message timestamps above chat bubbles"
```

---

## 任务 9：前端 /clear 命令实现

**文件：**
- 修改：`intellimate-web/src/components/CommandPopup.tsx`
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`（或 App.tsx 中的 sendMessage 处理）

- [ ] **步骤 1：确认 CommandPopup 中已有 /clear 命令**

检查 `CommandPopup.tsx` 中的命令列表，如果没有 `/clear`，添加：

```typescript
{ command: "/clear", description: "归档当前对话，开始新话题" },
```

- [ ] **步骤 2：在 sendMessage 处理链中拦截 /clear**

在 `useWebSocket` 的 `sendMessage` 回调中，添加 /clear 的拦截逻辑：

```typescript
const sendMessage = useCallback(
  (text: string, forcePlan?: boolean) => {
    if (text === "/clear") {
      const agentName = useAgentStore.getState().activeAgent;
      if (!agentName) return;
      import("../lib/sessionApi").then(({ clearSession }) => {
        clearSession(agentName).then(() => {
          const store = useChatStore.getState();
          store.clearMessages();
          store.setHistoryLoaded(true);
          store.addSystemMessage("对话已归档，开始新话题");
        });
      });
      return;
    }
    // ... 原有发送逻辑
  },
  [/* deps */],
);
```

- [ ] **步骤 3：验证 /clear 功能**

在浏览器中输入 `/clear` 发送，确认：
1. 消息列表清空
2. 显示系统消息"对话已归档，开始新话题"
3. 后续可正常继续对话

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/components/CommandPopup.tsx
git add intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat(web): implement /clear command for conversation archiving"
```

---

## 任务 10：前端消息重新生成

**文件：**
- 修改：`intellimate-web/src/components/MessageBubble.tsx`
- 修改：`intellimate-web/src/components/MessageList.tsx`

- [ ] **步骤 1：在 MessageBubble 中添加重新生成按钮**

在助手消息气泡下方（content div 结束后），添加重新生成按钮的条件渲染。需要通过 props 传入 `isLast` 和 `onRegenerate` 回调：

```tsx
interface MessageBubbleProps {
  message: ChatMessage;
  isLastAssistantWithTools?: boolean;
  isLastAssistant?: boolean;
  onRegenerate?: () => void;
}
```

在组件底部（时间戳之后，`totalTurns` 显示之后）添加：

```tsx
{!isUser && isLastAssistant && !message.streaming && message.content && (
  <button
    type="button"
    onClick={onRegenerate}
    className="mt-1 text-[11px] text-slate-400 hover:text-blue-500 transition-colors flex items-center gap-1"
  >
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="1 4 1 10 7 10" />
      <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
    </svg>
    重新生成
  </button>
)}
```

- [ ] **步骤 2：在 MessageList 中传入 isLastAssistant 和 onRegenerate**

在 `MessageList.tsx` 中渲染消息列表时，识别最后一条助手消息并传入回调：

```tsx
const lastAssistantIdx = messages.findLastIndex((m) => m.role === "assistant");

{messages.map((msg, idx) => (
  <MessageBubble
    key={msg.id}
    message={msg}
    isLastAssistant={idx === lastAssistantIdx}
    onRegenerate={idx === lastAssistantIdx ? handleRegenerate : undefined}
    // ... 其他 props
  />
))}
```

`handleRegenerate` 回调：找到最后一条用户消息的内容，移除最后一条助手消息，重新发送。

```typescript
const handleRegenerate = useCallback(() => {
  const msgs = useChatStore.getState().messages;
  const lastUserMsg = [...msgs].reverse().find((m) => m.role === "user");
  if (!lastUserMsg) return;
  // 移除最后一条助手消息
  useChatStore.getState().removeLastAssistantMessage();
  // 重新发送
  onSend(lastUserMsg.content, false, true); // 第三个参数 = regenerate
}, [onSend]);
```

- [ ] **步骤 3：在 chatStore 中添加 `removeLastAssistantMessage` 方法**

```typescript
removeLastAssistantMessage: () => {
  const agent = get().currentAgent;
  set((state) => {
    const msgs = [...(state.messagesByAgent[agent] ?? [])];
    const lastIdx = msgs.findLastIndex((m) => m.role === "assistant");
    if (lastIdx >= 0) msgs.splice(lastIdx, 1);
    return {
      messagesByAgent: { ...state.messagesByAgent, [agent]: msgs },
      messages: msgs,
      isWaiting: false,
    };
  });
},
```

- [ ] **步骤 4：在 `sendMessage` 中传递 regenerate 参数到 WebSocket 请求**

```typescript
// 在 conversation.message 请求 params 中：
const req = createRequest("conversation.message", {
  text,
  channelId: "webchat",
  contextType: "dm",
  agentName: currentAgent,
  ...(forcePlan ? { forcePlan: true } : {}),
  ...(regenerate ? { regenerate: true } : {}),
});
```

- [ ] **步骤 5：编译验证**

运行：`cd intellimate-web && npx tsc --noEmit`

预期：无类型错误

- [ ] **步骤 6：Commit**

```bash
git add intellimate-web/src/components/MessageBubble.tsx
git add intellimate-web/src/components/MessageList.tsx
git add intellimate-web/src/stores/chatStore.ts
git add intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat(web): add message regeneration support"
```

---

## 任务 11：前端 MessageList 向上翻页加载

**文件：**
- 修改：`intellimate-web/src/components/MessageList.tsx`

- [ ] **步骤 1：添加 IntersectionObserver 检测滚动到顶部**

在 MessageList 组件中添加一个 sentinel 元素在列表顶部，当它进入视口时触发加载更多：

```tsx
const sentinelRef = useRef<HTMLDivElement>(null);
const { historyHasMore, loadingHistory, loadMoreHistory } = useChatStore(
  useShallow((s) => ({
    historyHasMore: s.historyHasMore,
    loadingHistory: s.loadingHistory,
    loadMoreHistory: s.loadMoreHistory,
  }))
);
const activeAgent = useAgentStore((s) => s.activeAgent);

useEffect(() => {
  if (!sentinelRef.current || !historyHasMore) return;
  const observer = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting && !loadingHistory && activeAgent) {
        loadMoreHistory(activeAgent);
      }
    },
    { threshold: 0.1 },
  );
  observer.observe(sentinelRef.current);
  return () => observer.disconnect();
}, [historyHasMore, loadingHistory, activeAgent, loadMoreHistory]);
```

在消息列表 JSX 顶部添加 sentinel：

```tsx
<div ref={sentinelRef} className="h-1" />
{loadingHistory && (
  <div className="flex justify-center py-2">
    <div className="text-xs text-slate-400">加载中...</div>
  </div>
)}
```

- [ ] **步骤 2：验证向上滚动加载**

在浏览器中：
1. 先发送足够多的消息（>50 条）
2. /clear 后重新连接
3. 向上滚动到顶部
4. 确认自动加载更早的消息

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/components/MessageList.tsx
git commit -m "feat(web): add infinite scroll for loading older messages"
```

---

## 任务 12：前端 URL 路由引入

**文件：**
- 修改：`intellimate-web/package.json`
- 修改：`intellimate-web/src/main.tsx`
- 修改：`intellimate-web/src/App.tsx`
- 修改：`intellimate-web/src/components/Sidebar.tsx`

- [ ] **步骤 1：安装 react-router-dom**

运行：`cd intellimate-web && npm install react-router-dom`

- [ ] **步骤 2：在 main.tsx 中包裹 BrowserRouter**

```tsx
import { BrowserRouter } from "react-router-dom";

createRoot(document.getElementById("root")!).render(
  <BrowserRouter>
    <App />
  </BrowserRouter>
);
```

- [ ] **步骤 3：重构 App.tsx 使用 Routes**

将 App.tsx 中的 `viewMode` 状态和条件渲染替换为 `<Routes>` 结构：

```tsx
import { Routes, Route, Navigate } from "react-router-dom";

// 移除 viewMode state 和相关逻辑
// 保留 darkMode, sidebarOpen, agentConfigTarget, createModalOpen, planPanel 状态

return (
  <div className="flex h-screen overflow-hidden bg-white dark:bg-slate-900">
    <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} ... />
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      <TopBar ... />
      <div className="flex flex-1 min-h-0">
        <Routes>
          <Route path="/" element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<ChatPanel onSend={sendMessage} onCancel={cancelRequest} onSendPlanAction={sendPlanAction} />} />
          <Route path="/history" element={<HistoryPage />} />
          <Route path="/history/chat/:sessionId" element={<ArchivedChatView />} />
          <Route path="/agents" element={<AgentCardGrid ... />} />
          <Route path="/tools" element={<ToolManagerPage onBack={() => navigate("/chat")} />} />
          <Route path="/skills" element={<SkillManagerPage onBack={() => navigate("/chat")} />} />
          <Route path="/models" element={<ModelManagerPage onBack={() => navigate("/chat")} />} />
          <Route path="/memory" element={<MemoryManagerPage activeAgent={activeAgent ?? undefined} />} />
          <Route path="/scheduler" element={<SchedulerDashboard />} />
        </Routes>
        {showPlanPanel && location.pathname === "/chat" && (
          // PlanPanel 渲染逻辑保持不变
        )}
      </div>
    </div>
    ...
  </div>
);
```

- [ ] **步骤 4：修改 Sidebar 导航项为 Link**

将 Sidebar.tsx 中的按钮改为使用 `useNavigate` 或 `<Link>`：

```tsx
import { useNavigate, useLocation } from "react-router-dom";

// 在组件内：
const navigate = useNavigate();
const location = useLocation();

// 每个导航按钮改为：
<button
  onClick={() => { navigate("/tools"); onClose(); }}
  className={`... ${location.pathname === "/tools" ? "bg-slate-200/80 dark:bg-slate-700" : ""}`}
>
```

- [ ] **步骤 5：编译验证**

运行：`cd intellimate-web && npx tsc --noEmit`

预期：无类型错误

- [ ] **步骤 6：手动测试路由**

浏览器访问：
- `http://localhost:5173/` → 重定向到 `/chat`
- `http://localhost:5173/tools` → 工具管理页面
- 浏览器后退 → 回到上一页
- 刷新 → 停留在当前页面

- [ ] **步骤 7：Commit**

```bash
git add intellimate-web/package.json intellimate-web/package-lock.json
git add intellimate-web/src/main.tsx
git add intellimate-web/src/App.tsx
git add intellimate-web/src/components/Sidebar.tsx
git commit -m "feat(web): introduce react-router-dom for URL-based navigation"
```

---

## 任务 13：历史界面（对话 + 计划整合）

**文件：**
- 创建：`intellimate-web/src/components/HistoryPage.tsx`
- 创建：`intellimate-web/src/components/ArchivedChatView.tsx`

- [ ] **步骤 1：创建 HistoryPage 组件**

```tsx
import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAgentStore } from "../stores/agentStore";
import { fetchArchivedSessions, type ArchivedSession } from "../lib/sessionApi";
import { MessageSquare, ClipboardList, Search, ChevronLeft } from "lucide-react";

type TabType = "chats" | "plans";

export default function HistoryPage() {
  const navigate = useNavigate();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const [tab, setTab] = useState<TabType>("chats");
  const [sessions, setSessions] = useState<ArchivedSession[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");

  useEffect(() => {
    if (!activeAgent) return;
    setLoading(true);
    fetchArchivedSessions(activeAgent, 50, 0)
      .then((resp) => setSessions(resp.sessions))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [activeAgent]);

  const filteredSessions = searchQuery
    ? sessions.filter((s) => s.title?.includes(searchQuery))
    : sessions;

  return (
    <div className="flex-1 overflow-y-auto p-6 max-w-3xl mx-auto w-full">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate("/chat")} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
          <ChevronLeft size={18} className="text-slate-500" />
        </button>
        <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">历史</h1>
      </div>

      {/* 搜索栏 */}
      <div className="relative mb-4">
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="搜索历史对话..."
          className="w-full pl-9 pr-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 placeholder-slate-400 outline-none focus:border-blue-400"
        />
      </div>

      {/* Tab 切换 */}
      <div className="flex gap-1 mb-4 p-1 bg-slate-100 dark:bg-slate-800 rounded-lg">
        <button
          onClick={() => setTab("chats")}
          className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
            tab === "chats" ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm" : "text-slate-500"
          }`}
        >
          <MessageSquare size={14} /> 对话
        </button>
        <button
          onClick={() => setTab("plans")}
          className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
            tab === "plans" ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm" : "text-slate-500"
          }`}
        >
          <ClipboardList size={14} /> 计划
        </button>
      </div>

      {/* 内容区 */}
      {tab === "chats" && (
        <div className="space-y-2">
          {loading && <p className="text-sm text-slate-400 text-center py-4">加载中...</p>}
          {!loading && filteredSessions.length === 0 && (
            <p className="text-sm text-slate-400 text-center py-8">暂无归档对话</p>
          )}
          {filteredSessions.map((session) => (
            <button
              key={session.id}
              onClick={() => navigate(`/history/chat/${session.id}`)}
              className="w-full text-left p-3 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
            >
              <p className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                {session.title || "无标题对话"}
              </p>
              <p className="text-xs text-slate-400 mt-1">
                {new Date(session.lastActiveAt).toLocaleString("zh-CN")}
              </p>
            </button>
          ))}
        </div>
      )}

      {tab === "plans" && (
        <div className="text-sm text-slate-400 text-center py-8">
          {/* 计划历史复用现有 PlanHistoryTab 的逻辑 */}
          计划历史将在此显示（复用现有 PlanHistoryTab 数据）
        </div>
      )}
    </div>
  );
}
```

- [ ] **步骤 2：创建 ArchivedChatView 组件**

```tsx
import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { fetchSessionMessages, type HistoryMessage } from "../lib/sessionApi";
import { ChevronLeft, Bot, User } from "lucide-react";

export default function ArchivedChatView() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [messages, setMessages] = useState<HistoryMessage[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!sessionId) return;
    setLoading(true);
    fetchSessionMessages(parseInt(sessionId, 10), 200)
      .then((resp) => setMessages(resp.messages))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [sessionId]);

  return (
    <div className="flex-1 overflow-y-auto p-6 max-w-3xl mx-auto w-full">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate("/history")} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
          <ChevronLeft size={18} className="text-slate-500" />
        </button>
        <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">归档对话</h1>
        <span className="text-xs text-slate-400">只读</span>
      </div>

      {loading && <p className="text-sm text-slate-400 text-center py-8">加载中...</p>}

      <div className="space-y-4">
        {messages
          .filter((m) => m.role === "user" || m.role === "assistant")
          .map((msg) => (
            <div key={msg.id} className={`flex gap-3 ${msg.role === "user" ? "flex-row-reverse" : ""}`}>
              <div className={`flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center ${
                msg.role === "user" ? "bg-blue-500 text-white" : "bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
              }`}>
                {msg.role === "user" ? <User size={14} /> : <Bot size={14} />}
              </div>
              <div className="max-w-[75%]">
                <div className="text-[10px] text-slate-400 mb-0.5">
                  {new Date(msg.createdAt).toLocaleString("zh-CN")}
                </div>
                <div className={`rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap ${
                  msg.role === "user"
                    ? "bg-blue-500 text-white"
                    : "bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100"
                }`}>
                  {msg.content}
                </div>
              </div>
            </div>
          ))}
      </div>
    </div>
  );
}
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-web && npx tsc --noEmit`

预期：无类型错误

- [ ] **步骤 4：手动测试历史界面**

1. 先发送一些消息
2. 执行 `/clear`
3. 打开 `/history`
4. 确认归档对话出现在列表中
5. 点击归档对话，确认只读视图正常显示

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/components/HistoryPage.tsx
git add intellimate-web/src/components/ArchivedChatView.tsx
git commit -m "feat(web): add unified history page with archived chat view"
```

---

## 任务 14：后端兼容性——resolveSession 适配 status 字段

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java`

- [ ] **步骤 1：修改 resolveSession 逻辑确保只用活跃 session**

当前 `resolveSession` 通过 `(channelId, contextType, contextId)` 查找 session。引入 status 后，需要确保：
1. 只查找 `status = 'active'` 的 session
2. 旧数据（没有 status 字段值的）自动视为 active

修改 `getOrCreate` 方法，在创建新 session 时设置 `status = "active"`：

```java
@Override
public Mono<SessionEntity> getOrCreate(SessionKey key, SessionMetadata metadata) {
    return sessionRepository.findBySessionKey(key.channelId(), key.contextType(), key.contextId())
            .flatMap(existing -> {
                existing.setLastActiveAt(LocalDateTime.now());
                // 确保旧 session 有 status
                if (existing.getStatus() == null) {
                    existing.setStatus("active");
                }
                return sessionRepository.save(existing);
            })
            .switchIfEmpty(Mono.defer(() -> {
                SessionEntity session = new SessionEntity();
                session.setChannelId(key.channelId());
                session.setContextType(key.contextType());
                session.setContextId(key.contextId());
                session.setAgentName(metadata.agentName());
                session.setStatus("active");
                session.setLastActiveAt(LocalDateTime.now());
                session.setCreatedAt(LocalDateTime.now());
                session.setDeleted(0);
                return sessionRepository.save(session)
                        .doOnSuccess(s -> log.info("Created new session: id={}, key={}",
                                s.getId(), key.toCompositeKey()));
            }));
}
```

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-gateway && mvn compile`

预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java
git commit -m "fix(session): ensure active status set on session creation and legacy data"
```

---

## 任务 15：后端 regenerate 标志支持

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：在 processMessageStreaming 中处理 regenerate 参数**

在 `MessagePipeline` 处理 `conversation.message` 请求时，检查 `params` 中是否有 `regenerate: true`。如果有：
- 跳过用户消息的 `appendMessage` 保存（因为 transcript 中已有该条用户消息）
- 其余流程不变（照常调用 AgentRuntime）

在构建 `InboundEnvelope` 或调用 `appendMessage` 之前添加判断：

```java
boolean isRegenerate = Boolean.TRUE.equals(params.get("regenerate"));

// 只有非 regenerate 时才保存用户消息到 transcript
if (!isRegenerate) {
    TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
    userMsg.setRole("user");
    userMsg.setContent(text);
    userMsg.setCreatedAt(LocalDateTime.now());
    sessionManager.appendMessage(sessionId, userMsg).subscribe();
}
```

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-gateway && mvn compile`

预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git commit -m "feat(pipeline): support regenerate flag to skip duplicate user message save"
```

---

## 任务 16：端到端验证

- [ ] **步骤 1：完整启动后端和前端**

```bash
# 终端 1
cd /Users/user/Documents/code/GitHub/IntelliMate && mvn install -DskipTests && mvn spring-boot:run -pl intellimate-gateway

# 终端 2
cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npm run dev
```

- [ ] **步骤 2：验证消息持久化**

1. 打开 `http://localhost:5173/chat`
2. 发送几条消息，等待 AI 回复
3. 刷新页面（F5）
4. 确认消息仍然显示（从服务端加载）

- [ ] **步骤 3：验证 /clear 归档**

1. 在输入框输入 `/clear` 发送
2. 确认消息列表清空，显示系统消息
3. 导航到 `/history`
4. 确认刚才的对话出现在列表中
5. 点击进入，确认可以只读查看

- [ ] **步骤 4：验证重新生成**

1. 发送一条消息，等待 AI 回复
2. 在最后一条助手消息下方点击"重新生成"
3. 确认旧回复消失，新回复生成

- [ ] **步骤 5：验证 URL 路由**

1. 点击侧边栏"工具管理"，确认 URL 变为 `/tools`
2. 浏览器后退，确认回到 `/chat`
3. 手动输入 `http://localhost:5173/memory` 访问
4. 确认直接到达记忆观测页面

- [ ] **步骤 6：验证消息时间戳**

1. 确认每条消息气泡上方显示时间
2. 当天消息仅显示 HH:mm
3. 加载的历史消息（非当天）显示 MM-DD HH:mm

- [ ] **步骤 7：最终 Commit（如有修复）**

```bash
git add -A
git commit -m "fix: address integration issues from end-to-end testing"
```

---

## 依赖关系

```
任务 1 (DB schema)
  └──▶ 任务 2 (Repository) + 任务 14 (兼容性)
        └──▶ 任务 3 (SessionManager 逻辑)
              └──▶ 任务 4 (REST Controller)
                    └──▶ 任务 5 (前端 API 客户端)
                          └──▶ 任务 6-7 (ChatStore + WebSocket 加载)
                                └──▶ 任务 9 (/clear)
                                └──▶ 任务 11 (翻页加载)

任务 8 (时间戳) ── 独立，随时可做
任务 10 (重新生成) ── 依赖任务 15 (后端 regenerate)
任务 12 (URL 路由) ── 独立，随时可做
任务 13 (历史界面) ── 依赖任务 5 + 12

任务 16 (端到端验证) ── 所有任务完成后
```
