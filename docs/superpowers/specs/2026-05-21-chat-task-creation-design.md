# 对话创建任务 — 设计规格

**日期：** 2026-05-21  
**状态：** 待审阅  
**作者：** AI Assistant + 用户协作  

## 概述

让 AI 模型在对话中通过 Function Calling 创建和管理待办任务（agent_task）及定时任务（scheduled_job_config），并在聊天界面中以结构化卡片展示结果。

## 背景

当前 IntelliMate 的待办任务和定时任务只能通过前端 UI 手动创建：

- **待办任务：** TaskManager.tsx → TaskController REST API → agent_task 表
- **定时任务：** SchedulerDashboard → ScheduledJobController REST API → scheduled_job_config 表

用户希望在与 Agent 对话时，模型能自动识别任务意图并创建任务，同时支持全部 CRUD 操作。

## 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 任务类型范围 | 待办 + 定时（两者） | 覆盖一次性提醒和周期性自动任务 |
| 触发方式 | 主动识别 + 明确指令结合 | 主动识别时先确认，明确指令直接创建 |
| 技术路径 | Agent Tool（Function Calling） | 复用现有 AgentRuntime 工具体系，schema 结构化 |
| 前端展示 | 结构化卡片嵌入聊天 | 比纯文本信息密度更高，支持快捷操作 |
| CRUD 范围 | 全部（创建/查询/更新/删除） | 完整的对话式任务管理 |
| 定时任务类型 | agent-prompt + http-callback | 覆盖最常用的两种场景 |
| 工具粒度 | 细粒度（8 个工具） | 每个工具 schema 简单明确，LLM 调用准确率高 |

## 架构

### 数据流

```
用户对话 → AgentRuntime(LLM) → function calling → TaskManagementTool / ScheduledJobManagementTool
    → Repository.save() → DB
    → 工具返回 JSON → LLM 生成自然语言回复
    → 前端检测 tool_call → 渲染结构化卡片 + 文本回复
```

### 模块归属

- **工具类：** `intellimate-agent` 模块（与 ExecTool、WritePlanTool 同级）
- **Repository 依赖：** 通过 `@Autowired(required = false)` 注入 gateway 的 Repository
- **前端组件：** `intellimate-web/src/components/chat/` 目录

### 与现有系统的关系

- 工具创建的待办任务与 UI 创建的完全一致，心跳引擎同样会在到期时提醒
- 工具创建的定时任务与 UI 创建的一致，调度引擎热加载后立即生效
- 工具创建的定时任务产出通过已实现的 ChatInjectionService 注入聊天

## 后端设计

### 1. TaskManagementTool（待办任务）

**文件：** `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/TaskManagementTool.java`

```java
@Component
public class TaskManagementTool {

    private final AgentTaskRepository taskRepo;

    public TaskManagementTool(@Autowired(required = false) AgentTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    @Tool(description = "创建待办任务/提醒。用于用户需要记录待办事项或设置一次性提醒的场景。"
            + "示例意图：'明天下午3点提醒我开会'、'记下买牛奶'、'添加一个待办：写周报'")
    public String createTodoTask(
        @ToolParam(description = "任务标题，简明扼要描述要做的事") String title,
        @ToolParam(description = "任务详细描述（可选）", required = false) String description,
        @ToolParam(description = "截止时间，ISO 8601 格式如 2026-05-22T15:00:00（可选）", required = false) String dueAt,
        @ToolParam(description = "提醒时间，ISO 8601 格式，应早于或等于 dueAt（可选）", required = false) String remindAt,
        @ToolParam(description = "优先级：0=普通 1=重要 2=紧急，默认 0", required = false) Integer priority
    ) { ... }

    @Tool(description = "查询当前 Agent 的待办任务列表。可按状态筛选。")
    public String listTodoTasks(
        @ToolParam(description = "按状态筛选：pending/done/cancelled/all，默认 pending", required = false) String status,
        @ToolParam(description = "返回数量上限，默认 20", required = false) Integer limit
    ) { ... }

    @Tool(description = "更新待办任务。可修改标题、描述、时间、优先级或标记完成/取消。")
    public String updateTodoTask(
        @ToolParam(description = "任务 ID") Long taskId,
        @ToolParam(description = "新标题", required = false) String title,
        @ToolParam(description = "新描述", required = false) String description,
        @ToolParam(description = "新截止时间，ISO 8601 格式", required = false) String dueAt,
        @ToolParam(description = "新提醒时间，ISO 8601 格式", required = false) String remindAt,
        @ToolParam(description = "新优先级：0/1/2", required = false) Integer priority,
        @ToolParam(description = "新状态：done 或 cancelled", required = false) String status
    ) { ... }

    @Tool(description = "删除待办任务。")
    public String deleteTodoTask(
        @ToolParam(description = "要删除的任务 ID") Long taskId
    ) { ... }
}
```

**返回值格式（统一 JSON）：**

创建成功：
```json
{
  "success": true,
  "action": "created",
  "task": {
    "id": 42,
    "title": "给客户打电话",
    "description": null,
    "dueAt": "2026-05-22T15:00:00",
    "remindAt": "2026-05-22T14:50:00",
    "priority": 1,
    "status": "pending"
  }
}
```

查询结果：
```json
{
  "success": true,
  "action": "listed",
  "tasks": [ ... ],
  "total": 3
}
```

错误：
```json
{
  "success": false,
  "error": "任务不存在：ID=99"
}
```

### 2. ScheduledJobManagementTool（定时任务）

**文件：** `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ScheduledJobManagementTool.java`

```java
@Component
public class ScheduledJobManagementTool {

    private final ScheduledJobConfigRepository jobRepo;
    private final ReactiveScheduleEngine scheduleEngine;

    public ScheduledJobManagementTool(
            @Autowired(required = false) ScheduledJobConfigRepository jobRepo,
            @Autowired(required = false) ReactiveScheduleEngine scheduleEngine) {
        this.jobRepo = jobRepo;
        this.scheduleEngine = scheduleEngine;
    }

    @Tool(description = "创建定时任务，支持两种类型："
            + "1) agent-prompt：让 Agent 按计划定时执行提示词（如'每天早上发新闻摘要'）"
            + "2) http-callback：定时调用外部 HTTP 接口（如'每小时检查某个 API'）")
    public String createScheduledJob(
        @ToolParam(description = "任务显示名称，描述这个定时任务的用途") String displayName,
        @ToolParam(description = "触发类型：CRON（cron 表达式）、FIXED_RATE（固定频率秒数）、FIXED_DELAY（固定延迟秒数）") String triggerType,
        @ToolParam(description = "触发值：CRON 类型填 cron 表达式如 '0 0 9 * * ?'；FIXED_RATE/FIXED_DELAY 填秒数如 '3600'") String triggerValue,
        @ToolParam(description = "任务类型：agent-prompt 或 http-callback") String jobType,
        @ToolParam(description = "任务参数 JSON：agent-prompt 需要 {\"prompt\":\"...\"}；http-callback 需要 {\"url\":\"...\",\"method\":\"GET/POST\"}") String paramsJson
    ) { ... }

    @Tool(description = "查询定时任务列表。返回所有可见的定时任务及其状态。")
    public String listScheduledJobs(
        @ToolParam(description = "按启用状态筛选：true/false/all，默认 all", required = false) String enabled,
        @ToolParam(description = "按任务组筛选：user-chat/custom/system/all，默认 all", required = false) String jobGroup
    ) { ... }

    @Tool(description = "更新定时任务配置。可修改触发规则、启用/禁用、参数。系统内置任务不可修改。")
    public String updateScheduledJob(
        @ToolParam(description = "任务名称（唯一标识）") String jobName,
        @ToolParam(description = "新显示名称", required = false) String displayName,
        @ToolParam(description = "新触发类型", required = false) String triggerType,
        @ToolParam(description = "新触发值", required = false) String triggerValue,
        @ToolParam(description = "启用或禁用：true/false", required = false) Boolean enabled,
        @ToolParam(description = "新参数 JSON", required = false) String paramsJson
    ) { ... }

    @Tool(description = "删除定时任务。系统内置任务（heartbeat-tick、memory-nightly-maintenance、data-cleanup）不可删除。")
    public String deleteScheduledJob(
        @ToolParam(description = "要删除的任务名称") String jobName
    ) { ... }
}
```

**返回值格式：**

创建成功：
```json
{
  "success": true,
  "action": "created",
  "job": {
    "jobName": "chat-20260521-a1b2c3",
    "displayName": "每日新闻摘要",
    "triggerType": "CRON",
    "triggerValue": "0 0 9 * * ?",
    "triggerDescription": "每天 09:00",
    "jobType": "agent-prompt",
    "enabled": true
  }
}
```

### 3. 工具注册

**修改文件：** `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolAutoConfiguration.java`

在 `toolCallbackProvider` 的参数和 tools 列表中添加：

```java
@Autowired(required = false) TaskManagementTool taskManagementTool,
@Autowired(required = false) ScheduledJobManagementTool scheduledJobManagementTool
```

使用 `required = false` 确保在无 gateway 依赖时不阻断启动。添加到 tools 列表时需判空。

### 4. ToolGroup 扩展

```java
TASK("任务管理", Set.of(
    "createTodoTask", "listTodoTasks", "updateTodoTask", "deleteTodoTask",
    "createScheduledJob", "listScheduledJobs", "updateScheduledJob", "deleteScheduledJob"
)),
```

### 5. ToolProfile 扩展

```java
FULL(Set.of(..., "createTodoTask", "listTodoTasks", "updateTodoTask", "deleteTodoTask",
     "createScheduledJob", "listScheduledJobs", "updateScheduledJob", "deleteScheduledJob")),
```

### 6. AgentId 上下文传递

工具执行时需要知道当前 agentId。方案：

在 `AgentRuntime.doExecuteTool()` 执行工具前，将 agentId 设置到 Reactor 的 `Context` 或 `ThreadLocal` 中，工具通过静态方法获取。

具体实现：新增 `AgentContext` 工具类：

```java
public class AgentContext {
    private static final ThreadLocal<Long> AGENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_NAME = new ThreadLocal<>();

    public static void set(Long agentId, String agentName) { ... }
    public static Long getAgentId() { return AGENT_ID.get(); }
    public static String getAgentName() { return AGENT_NAME.get(); }
    public static void clear() { AGENT_ID.remove(); AGENT_NAME.remove(); }
}
```

在 `AgentRuntime.doExecuteTool()` 执行工具前通过 `AgentContext.set(agentId, agentName)` 设置，执行后通过 `AgentContext.clear()` 清理。

TaskManagementTool 通过 `AgentContext.getAgentId()` 获取当前 agentId 来确定待办任务归属。
ScheduledJobManagementTool 创建 agent-prompt 类型定时任务时，通过 `AgentContext.getAgentName()` 获取当前 agent 名称写入 `paramsJson` 的 `agentName` 字段。

## 前端设计

### 1. 卡片组件

**新建文件：**
- `intellimate-web/src/components/chat/TaskCard.tsx` — 待办任务卡片
- `intellimate-web/src/components/chat/ScheduledJobCard.tsx` — 定时任务卡片

### 2. 卡片样式

**待办任务卡片（创建/更新结果）：**

```
┌──────────────────────────────────────┐
│ ✅ 待办任务已创建                      │
│                                      │
│ 📝 给客户打电话                       │
│ ⏰ 截止：2026-05-22 15:00            │
│ 🔔 提醒：2026-05-22 14:50            │
│ 🔴 优先级：重要                       │
│                                      │
│ [标记完成]  [编辑]  [取消]             │
└──────────────────────────────────────┘
```

**定时任务卡片：**

```
┌──────────────────────────────────────┐
│ ⏱️ 定时任务已创建                      │
│                                      │
│ 📋 每日新闻摘要                       │
│ 🔄 每天 09:00 执行                    │
│ 🤖 类型：Agent 提示词                  │
│ ✅ 状态：已启用                        │
│                                      │
│ [暂停]  [编辑]  [删除]                 │
└──────────────────────────────────────┘
```

**列表卡片：**

```
┌──────────────────────────────────────┐
│ 📋 你的待办任务（3 项）                │
│                                      │
│ 1. 🔴 给客户打电话   截止：明天 15:00  │
│ 2. 🟡 准备周报       截止：周五 18:00  │
│ 3. ⚪ 买咖啡豆       无截止时间         │
│                                      │
│ [查看全部]                            │
└──────────────────────────────────────┘
```

### 3. 卡片触发机制

在聊天消息渲染组件中，检测 tool_call 的工具名：

```typescript
const TASK_TOOL_NAMES = new Set([
  'createTodoTask', 'listTodoTasks', 'updateTodoTask', 'deleteTodoTask',
  'createScheduledJob', 'listScheduledJobs', 'updateScheduledJob', 'deleteScheduledJob'
]);

// 在消息渲染中
if (toolCall && TASK_TOOL_NAMES.has(toolCall.name)) {
  const result = JSON.parse(toolCall.result);
  if (toolCall.name.includes('Todo')) {
    return <TaskCard action={result.action} data={result} />;
  } else {
    return <ScheduledJobCard action={result.action} data={result} />;
  }
}
```

### 4. 卡片交互

卡片上的按钮直接调用 REST API（不走 LLM），保证操作即时响应：

- **标记完成：** `PUT /api/tasks/{agentId}/{taskId}` body: `{ status: "done" }`
- **取消任务：** `PUT /api/tasks/{agentId}/{taskId}` body: `{ status: "cancelled" }`
- **暂停定时任务：** `POST /api/scheduled-jobs/{jobName}/pause`
- **恢复定时任务：** `POST /api/scheduled-jobs/{jobName}/resume`
- **编辑：** 弹出内联编辑表单
- **查看全部：** 跳转到 TaskManager / SchedulerDashboard

卡片操作后更新本地状态（乐观更新），失败时回滚。

## 系统提示词

### 工具使用指南

在 `prompts/tool-usage-guidelines.md` 中新增以下段落：

```markdown
## 任务管理工具

你有两组工具用于管理用户的任务：

### 待办任务（一次性提醒/待办）
- `createTodoTask` — 创建单次待办事项或提醒
- `listTodoTasks` — 查询待办列表
- `updateTodoTask` — 修改或标记完成/取消
- `deleteTodoTask` — 删除任务

### 定时任务（周期性自动执行）
- `createScheduledJob` — 创建定时执行的任务
- `listScheduledJobs` — 查询定时任务列表
- `updateScheduledJob` — 修改配置或暂停/恢复
- `deleteScheduledJob` — 删除定时任务

### 如何选择工具
- 一次性的提醒/备忘 → 待办任务（如"明天3点提醒我开会"）
- 周期性/重复执行 → 定时任务（如"每天早上9点给我发新闻"）
- 如果用户意图不明确，先询问再创建

### 创建确认规则
- 当用户**明确要求**创建任务时（如"帮我设一个提醒"、"创建一个定时任务"），直接调用工具创建
- 当你**主动识别**到潜在任务意图时（如用户随口提到"明天得记得开会"），先用自然语言确认后再创建
- 用户确认后再调用创建工具

### 时间处理
- 将用户的自然语言时间转换为 ISO 8601 格式传递给工具
- 定时任务的 cron 表达式参考：每天9点 = `0 0 9 * * ?`，每小时 = `0 0 * * * ?`，每周一9点 = `0 0 9 ? * MON`
- 固定频率用秒数：每小时 = 3600，每30分钟 = 1800
```

## 错误处理

| 错误场景 | 处理方式 |
|---------|---------|
| Repository 未注入（纯 agent 模式） | 工具返回 `{ "success": false, "error": "任务管理功能在当前环境不可用" }` |
| 时间格式无效 | 工具校验后返回 `{ "success": false, "error": "时间格式无效，请使用 ISO 8601 格式" }`，LLM 可重新填参 |
| taskId / jobName 不存在 | 返回 `{ "success": false, "error": "任务不存在" }` |
| 删除/修改系统内置任务 | 返回 `{ "success": false, "error": "系统任务不可删除或修改" }` |
| DB 写入失败 | 记录 ERROR 日志，返回错误提示，LLM 告知用户稍后重试 |
| 调度引擎通知失败 | 任务已持久化，记录 WARN 日志；引擎下次刷新自动加载 |

## 安全约束

1. **系统任务保护：** `heartbeat-tick`、`memory-nightly-maintenance`、`data-cleanup` 等系统任务不可通过工具删除或修改关键配置
2. **Agent 隔离：** 工具只能操作当前 Agent 归属的待办任务（agentId 从 AgentContext 获取）
3. **任务组标识：** 工具创建的定时任务 jobGroup 固定为 `"user-chat"`，可追溯来源
4. **http-callback URL 校验：** 拒绝内网地址（127.0.0.1、10.x、192.168.x 等），防止 SSRF 攻击

## 测试策略

### 单元测试
- `TaskManagementToolTest.java` — Mock Repository，覆盖 CRUD 全部操作 + 边界场景
- `ScheduledJobManagementToolTest.java` — Mock Repository + 调度引擎，覆盖两种 jobType + 安全约束

### 集成测试
- 端到端对话测试：发送包含任务意图的消息，验证工具被调用且 DB 有记录
- 定时任务创建后调度引擎是否实际执行

### 前端测试
- TaskCard / ScheduledJobCard 组件渲染测试
- 卡片按钮交互测试（Mock API 调用）

## 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-agent/.../tools/TaskManagementTool.java` | 新建 | 待办任务 CRUD 工具 |
| `intellimate-agent/.../tools/ScheduledJobManagementTool.java` | 新建 | 定时任务 CRUD 工具 |
| `intellimate-agent/.../tools/AgentContext.java` | 新建 | Agent 上下文 ThreadLocal 传递 |
| `intellimate-agent/.../tools/ToolAutoConfiguration.java` | 修改 | 注册新工具 |
| `intellimate-agent/.../tools/ToolGroup.java` | 修改 | 添加 TASK 分组 |
| `intellimate-agent/.../tools/ToolProfile.java` | 修改 | FULL profile 添加任务工具 |
| `intellimate-agent/.../runtime/AgentRuntime.java` | 修改 | doExecuteTool 中设置 AgentContext |
| `intellimate-agent/src/main/resources/prompts/tool-usage-guidelines.md` | 修改 | 添加任务工具使用指南 |
| `intellimate-web/src/components/chat/TaskCard.tsx` | 新建 | 待办任务卡片组件 |
| `intellimate-web/src/components/chat/ScheduledJobCard.tsx` | 新建 | 定时任务卡片组件 |
| `intellimate-web/src/components/chat/ChatMessage.tsx`（或对应渲染组件） | 修改 | 集成卡片渲染逻辑 |
| `intellimate-agent/.../tools/TaskManagementToolTest.java` | 新建 | 待办工具单元测试 |
| `intellimate-agent/.../tools/ScheduledJobManagementToolTest.java` | 新建 | 定时工具单元测试 |

## 待办任务与定时任务的区别

| 维度 | 待办任务（agent_task） | 定时任务（scheduled_job_config） |
|------|----------------------|-------------------------------|
| 本质 | 一次性提醒/待办事项 | 周期性自动执行的任务 |
| 触发 | 到时间通过心跳引擎提醒用户 | 按 cron/固定间隔自动执行 |
| 执行者 | 不执行动作，仅提醒 | Agent 自动执行提示词或 HTTP 调用 |
| 典型场景 | "明天下午3点提醒我开会" | "每天早上9点发新闻摘要" |
| 时间表达 | 具体的某个时间点 | "每天"、"每周一"、"每隔2小时" |
| 生命周期 | pending → done/cancelled | 持续运行，可暂停/恢复/删除 |
