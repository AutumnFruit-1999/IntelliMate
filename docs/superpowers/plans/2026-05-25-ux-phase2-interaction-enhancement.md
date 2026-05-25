# UX Phase 2: 交互增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 IntelliMate 的日常使用体验流畅、不阻塞、有感知——等待时可输入、浏览器后台有通知、历史可搜索、侧边栏精简高效。

**架构：** 纯前端改动为主（排队机制、浏览器通知、侧边栏折叠），搜索功能需新增后端 API。流式渲染已基于 `sanitizePartialMarkdown` + `useDeferredValue` 实现增量 Markdown，仅需微调。

**技术栈：** React 19 / TypeScript / Zustand / Tailwind 4 / react-router-dom v7 / Web Notifications API

**规格文档：** `docs/superpowers/specs/2026-05-25-user-experience-optimization-design.md` Phase 2 部分

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-web/src/hooks/useNotification.ts` | 浏览器通知权限管理和发送 |
| `intellimate-gateway/src/main/java/.../http/SearchController.java` | 全文搜索 REST API |

### 修改文件

| 文件 | 变更 |
|------|------|
| `intellimate-web/src/components/ComposeArea.tsx` | 等待时保持可编辑 + 排队发送 |
| `intellimate-web/src/stores/chatStore.ts` | 新增 queuedMessage 状态 |
| `intellimate-web/src/hooks/useWebSocket.ts` | agent.done 时自动发送排队消息 + 触发通知 |
| `intellimate-web/src/components/StreamingText.tsx` | 微调：段落级渲染优化 |
| `intellimate-web/src/components/Sidebar.tsx` | 管理区折叠/展开 |
| `intellimate-web/src/components/HistoryPage.tsx` | 接入后端搜索 API |
| `intellimate-gateway/.../repository/TranscriptMessageRepository.java` | 新增全文搜索查询 |

---

## 任务 1：等待时可输入（排队机制）

**文件：**
- 修改：`intellimate-web/src/components/ComposeArea.tsx`
- 修改：`intellimate-web/src/stores/chatStore.ts`
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`

- [ ] **步骤 1：chatStore 新增 queuedMessage 状态**

在 chatStore 中添加：

```typescript
queuedMessage: string | null;
setQueuedMessage: (msg: string | null) => void;
```

初始值：
```typescript
queuedMessage: null,
setQueuedMessage: (msg) => set({ queuedMessage: msg }),
```

- [ ] **步骤 2：修改 ComposeArea 等待时保持可编辑**

当前逻辑：`isWaiting` 时 textarea 被 `disabled`。

改为：
- textarea 始终可编辑（移除 `disabled={isWaiting}`）
- `isWaiting` 时发送按钮图标变为排队图标（Clock），tooltip 提示"回复完成后自动发送"
- 发送行为改变：`isWaiting` 时不直接发送，而是存到 `queuedMessage`
- placeholder 改为："输入消息... 回复中（发送后将排队等待）"

```tsx
import { Clock } from "lucide-react";

// textarea 移除 disabled 属性
<textarea
  ref={textareaRef}
  value={text}
  onChange={handleChange}
  onKeyDown={handleKeyDown}
  placeholder={isWaiting ? "输入消息...（发送后将排队等待）" : disabled ? "连接已断开..." : "输入消息... (/ 查看命令)"}
  rows={1}
  className="flex-1 bg-transparent resize-none outline-none text-sm ..."
/>

// 按钮逻辑调整
{isWaiting ? (
  queuedMessage ? (
    // 已有排队消息，显示取消排队按钮
    <button onClick={clearQueue} className="..." title="取消排队">
      <Square size={16} />
    </button>
  ) : text.trim() ? (
    // 有输入内容且等待中，显示排队发送按钮
    <button onClick={handleQueueSend} className="..." title="排队发送">
      <Clock size={16} />
    </button>
  ) : (
    // 无输入，显示取消按钮
    <button onClick={onCancel} className="..." title="取消">
      <Square size={16} />
    </button>
  )
) : (
  <button onClick={handleSubmit} disabled={!canSend} className="...">
    <Send size={16} />
  </button>
)}
```

handleQueueSend 逻辑：
```typescript
const handleQueueSend = useCallback(() => {
  const trimmed = text.trim();
  if (!trimmed) return;
  useChatStore.getState().setQueuedMessage(trimmed);
  setText("");
  if (textareaRef.current) textareaRef.current.style.height = "auto";
}, [text]);
```

当排队消息存在时，在 textarea 上方显示排队指示器：
```tsx
{queuedMessage && (
  <div className="flex items-center gap-2 px-4 py-1.5 text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-t-2xl border border-b-0 border-slate-200 dark:border-slate-700">
    <Clock size={12} />
    <span className="truncate">排队中：{queuedMessage}</span>
    <button onClick={clearQueue} className="ml-auto text-slate-400 hover:text-red-500">✕</button>
  </div>
)}
```

- [ ] **步骤 3：useWebSocket 中 agent.done 时自动发送排队消息**

在 `useWebSocket.ts` 中处理 `agent.done` 事件的位置，添加：

```typescript
case "agent.done": {
  useChatStore.getState().setWaiting(false);
  // 发送排队消息
  const queued = useChatStore.getState().queuedMessage;
  if (queued) {
    useChatStore.getState().setQueuedMessage(null);
    setTimeout(() => sendMessage(queued), 100);
  }
  break;
}
```

- [ ] **步骤 4：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npx tsc --noEmit`

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/components/ComposeArea.tsx intellimate-web/src/stores/chatStore.ts intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat(web): allow typing while waiting with message queueing"
```

---

## 任务 2：浏览器通知

**文件：**
- 创建：`intellimate-web/src/hooks/useNotification.ts`
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`

- [ ] **步骤 1：创建 useNotification hook**

```typescript
import { useRef, useCallback } from "react";

export function useNotification() {
  const permissionAsked = useRef(false);

  const requestPermission = useCallback(() => {
    if (permissionAsked.current) return;
    if (!("Notification" in window)) return;
    if (Notification.permission === "default") {
      permissionAsked.current = true;
      Notification.requestPermission();
    }
  }, []);

  const notify = useCallback((title: string, body: string) => {
    if (!("Notification" in window)) return;
    if (Notification.permission !== "granted") return;
    if (!document.hidden) return;

    const notification = new Notification(title, {
      body: body.slice(0, 100),
      icon: "/favicon.ico",
    });

    notification.onclick = () => {
      window.focus();
      notification.close();
    };

    setTimeout(() => notification.close(), 5000);
  }, []);

  return { requestPermission, notify };
}
```

- [ ] **步骤 2：在 useWebSocket 中集成通知**

在 `useWebSocket.ts` 中：
- import `useNotification`
- 在 hook 内调用 `const { requestPermission, notify } = useNotification();`
- 在 `agent.done` 事件处理中，第一次收到时调用 `requestPermission()`
- 如果 `document.hidden`，调用 `notify`：

```typescript
case "agent.done": {
  useChatStore.getState().setWaiting(false);
  requestPermission();
  
  // 页面不可见时发送通知
  if (document.hidden) {
    const msgs = useChatStore.getState().messages;
    const lastAssistant = [...msgs].reverse().find(m => m.role === "assistant");
    const agentName = useAgentStore.getState().activeAgent ?? "Agent";
    notify(
      `${agentName} 回复了你`,
      lastAssistant?.content?.slice(0, 50) ?? "新回复",
    );
  }
  
  // 发送排队消息
  const queued = useChatStore.getState().queuedMessage;
  if (queued) {
    useChatStore.getState().setQueuedMessage(null);
    setTimeout(() => sendMessage(queued), 100);
  }
  break;
}
```

- [ ] **步骤 3：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npx tsc --noEmit`

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/hooks/useNotification.ts intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat(web): add browser notifications for background replies"
```

---

## 任务 3：侧边栏管理区折叠

**文件：**
- 修改：`intellimate-web/src/components/Sidebar.tsx`

- [ ] **步骤 1：添加折叠状态**

在 Sidebar 组件中添加 state：
```typescript
const [managementExpanded, setManagementExpanded] = useState(false);
```

- [ ] **步骤 2：将管理区包裹在可折叠区域中**

当前管理区有 "Agent 配置"、"工具管理"、"Skills 管理"、"模型管理"、"记忆观测"、"调度中心" 六个按钮。

改为：
```tsx
<div>
  <button
    onClick={() => setManagementExpanded(!managementExpanded)}
    className="w-full flex items-center gap-2 px-3 py-2 text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider hover:text-slate-700 dark:hover:text-slate-200 transition-colors"
  >
    {managementExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
    管理
  </button>
  {managementExpanded && (
    <div className="space-y-1 ml-2">
      {/* 现有的 6 个管理按钮 */}
    </div>
  )}
</div>
```

需要导入 `ChevronDown` 和 `ChevronRight` from lucide-react（确认是否已导入）。

如果当前路由在管理页面内（/agents, /tools, /skills, /models, /memory, /scheduler），则默认展开。

```typescript
const managementPaths = ["/agents", "/tools", "/skills", "/models", "/memory", "/scheduler"];
const isInManagement = managementPaths.some(p => location.pathname === p);

const [managementExpanded, setManagementExpanded] = useState(isInManagement);

// 路由变化时同步
useEffect(() => {
  if (isInManagement && !managementExpanded) {
    setManagementExpanded(true);
  }
}, [isInManagement]);
```

- [ ] **步骤 3：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npx tsc --noEmit`

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/components/Sidebar.tsx
git commit -m "feat(web): collapse management section in sidebar by default"
```

---

## 任务 4：后端搜索 API

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/TranscriptMessageRepository.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SessionHistoryController.java`

- [ ] **步骤 1：在 TranscriptMessageRepository 中添加搜索方法**

```java
@Query("SELECT tm.* FROM transcript_message tm " +
       "INNER JOIN session s ON tm.session_id = s.id " +
       "WHERE s.agent_name = :agentName AND s.channel_id = 'webchat' AND s.deleted = 0 " +
       "AND tm.content LIKE CONCAT('%', :keyword, '%') " +
       "ORDER BY tm.created_at DESC LIMIT :limit")
Flux<TranscriptMessageEntity> searchByAgentNameAndKeyword(String agentName, String keyword, int limit);
```

- [ ] **步骤 2：在 SessionHistoryController 中添加搜索端点**

```java
@GetMapping("/{agentName}/search")
public Mono<Map<String, Object>> searchMessages(
        @PathVariable String agentName,
        @RequestParam String q,
        @RequestParam(defaultValue = "20") int limit) {
    if (q == null || q.isBlank()) {
        return Mono.just(Map.of("results", List.of()));
    }
    return transcriptRepository.searchByAgentNameAndKeyword(agentName, q.trim(), limit)
            .map(this::toMessageDto)
            .collectList()
            .map(results -> Map.<String, Object>of("results", results));
}
```

- [ ] **步骤 3：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate && mvn compile -pl intellimate-gateway -am`

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/TranscriptMessageRepository.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SessionHistoryController.java
git commit -m "feat(api): add message search endpoint"
```

---

## 任务 5：前端历史界面接入搜索

**文件：**
- 修改：`intellimate-web/src/lib/sessionApi.ts`
- 修改：`intellimate-web/src/components/HistoryPage.tsx`

- [ ] **步骤 1：在 sessionApi.ts 中添加搜索函数**

```typescript
export interface SearchResult {
  id: number;
  role: string;
  content: string;
  createdAt: string;
  toolName?: string;
}

export function searchMessages(
  agentName: string,
  query: string,
  limit = 20,
): Promise<{ results: SearchResult[] }> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/search?${params}`);
}
```

- [ ] **步骤 2：在 HistoryPage 中实现后端搜索**

当用户在搜索框输入时（防抖 300ms），调用后端搜索 API：

```typescript
import { searchMessages, type SearchResult } from "../lib/sessionApi";

// 在组件中：
const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
const [searching, setSearching] = useState(false);
const searchTimeoutRef = useRef<ReturnType<typeof setTimeout>>();

const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
  const query = e.target.value;
  setSearchQuery(query);
  
  if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);
  
  if (!query.trim() || !activeAgent) {
    setSearchResults([]);
    return;
  }
  
  searchTimeoutRef.current = setTimeout(() => {
    setSearching(true);
    searchMessages(activeAgent, query.trim())
      .then(resp => setSearchResults(resp.results))
      .catch(console.error)
      .finally(() => setSearching(false));
  }, 300);
};
```

当有搜索结果时，显示结果列表替代原来的 session 列表：

```tsx
{searchQuery.trim() && (
  <div className="space-y-2">
    {searching && <p className="text-sm text-slate-400 text-center py-2">搜索中...</p>}
    {!searching && searchResults.length === 0 && (
      <p className="text-sm text-slate-400 text-center py-4">无匹配结果</p>
    )}
    {searchResults.map((result) => (
      <div key={result.id} className="p-3 rounded-lg border border-slate-200 dark:border-slate-700">
        <p className="text-xs text-slate-400 mb-1">
          {result.role === "user" ? "你" : "助手"} · {new Date(result.createdAt).toLocaleString("zh-CN")}
        </p>
        <p className="text-sm text-slate-700 dark:text-slate-200 line-clamp-2">
          {result.content}
        </p>
      </div>
    ))}
  </div>
)}
```

- [ ] **步骤 3：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npx tsc --noEmit`

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/lib/sessionApi.ts intellimate-web/src/components/HistoryPage.tsx
git commit -m "feat(web): integrate search API into history page"
```

---

## 任务 6：流式渲染微调

**文件：**
- 修改：`intellimate-web/src/components/StreamingText.tsx`

- [ ] **步骤 1：评估当前实现**

当前 `StreamingText` 已经使用 `sanitizePartialMarkdown` + `useDeferredValue` 实现增量 Markdown 渲染。检查 `markdownSanitizer.ts` 确认其行为。

如果 `sanitizePartialMarkdown` 已经能处理不完整 Markdown（如未关闭的代码块、未完成的列表等），则无需大改。

- [ ] **步骤 2：添加段落稳定性优化**

当前渲染是整块 content 经过 sanitize 后传给 `RenderedMarkdown`。为减少重排，可将已完成的段落与最后一个进行中的段落分开渲染：

```typescript
export default function StreamingText({ content, streaming }: StreamingTextProps) {
  const deferredContent = useDeferredValue(content);

  if (!content && streaming) {
    return <div className="h-5" />;
  }

  if (streaming) {
    const sanitized = sanitizePartialMarkdown(deferredContent);
    const paragraphs = sanitized.split(/\n\n/);
    const completedParagraphs = paragraphs.slice(0, -1).join("\n\n");
    const lastParagraph = paragraphs[paragraphs.length - 1] ?? "";

    return (
      <div className="relative">
        {completedParagraphs && <RenderedMarkdown content={completedParagraphs} />}
        {lastParagraph && (
          <div className="prose prose-sm dark:prose-invert max-w-none break-words">
            <p className="whitespace-pre-wrap">{lastParagraph}<span className="cursor-blink text-blue-500 ml-0.5">█</span></p>
          </div>
        )}
      </div>
    );
  }

  return <RenderedMarkdown content={content} />;
}
```

注意：如果 `sanitizePartialMarkdown` 已经处理得很好，且实际使用中没有明显跳跃，可以保持当前实现不变，仅将光标从外部移入最后一个段落的 inline 位置。

- [ ] **步骤 3：编译验证**

运行：`cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npx tsc --noEmit`

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/components/StreamingText.tsx
git commit -m "refactor(web): optimize streaming text with paragraph-level stability"
```

---

## 任务 7：端到端验证

- [ ] **步骤 1：启动服务**

```bash
# 终端 1
cd /Users/user/Documents/code/GitHub/IntelliMate && mvn spring-boot:run -pl intellimate-gateway

# 终端 2
cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web && npm run dev
```

- [ ] **步骤 2：验证排队机制**

1. 发送消息，等待 AI 开始回复
2. 在等待过程中输入新消息
3. 确认输入框保持可编辑
4. 按回车发送（或点击排队按钮）
5. 确认消息进入排队状态（显示排队指示器）
6. 确认 AI 回复完成后排队消息自动发送

- [ ] **步骤 3：验证浏览器通知**

1. 发送消息
2. 切换到其他标签页（让 IntelliMate 进入后台）
3. 等待 AI 回复完成
4. 确认收到浏览器通知
5. 点击通知，确认回到 IntelliMate 页面

- [ ] **步骤 4：验证侧边栏折叠**

1. 确认管理区默认折叠
2. 点击"管理"可展开
3. 点击管理项导航后确认管理区自动展开

- [ ] **步骤 5：验证历史搜索**

1. 先有一些对话历史（通过 /clear 归档几轮对话）
2. 导航到 /history
3. 在搜索框输入关键词
4. 确认 300ms 后显示搜索结果
5. 清空搜索框后恢复 session 列表

- [ ] **步骤 6：验证流式渲染**

1. 发送一条会触发长回复的消息
2. 观察流式渲染过程
3. 确认已完成的段落不会重排
4. 确认光标在最后一个段落内

---

## 依赖关系

```
任务 1 (排队机制) ── 独立，可最先开始
任务 2 (浏览器通知) ── 依赖任务 1（共用 agent.done 处理逻辑）
任务 3 (侧边栏折叠) ── 独立
任务 4 (后端搜索 API) ── 独立
任务 5 (前端搜索接入) ── 依赖任务 4
任务 6 (流式渲染微调) ── 独立

任务 7 (验证) ── 所有任务完成后
```

推荐执行顺序：1 → 2 → 3 → 4 → 5 → 6 → 7
（任务 3/4/6 无依赖，可与其他任务并行）
