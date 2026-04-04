# Plan 模型测试对话

覆盖 Plan 模型核心生命周期的 6 组 WebSocket 测试对话。

**约定**：
- `->` 客户端发往服务器（`RequestFrame`）
- `<-` 服务器推送给客户端（`EventFrame` / `ResponseFrame`）
- `seq` 和 `id` 为示例值；实际由服务端 `AtomicLong` / 客户端 `crypto.randomUUID()` 生成

---

## 对话 1：完整 Happy Path（创建 → 审批 → 执行 → 完成）

场景：用户要求"帮我搭建一个 Spring Boot 项目"，Agent 生成 3 步计划并逐步执行完成。

### 1.1 用户发送消息

```json
-> {
  "type": "request",
  "id": "req-001",
  "method": "conversation.message",
  "params": {
    "text": "帮我搭建一个 Spring Boot 项目，包含 Web、JPA 和 Flyway",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "user-abc"
  }
}
```

### 1.2 Agent 回合开始

```json
<- {
  "type": "event",
  "event": "agent.turn_start",
  "payload": { "turn": 1, "maxTurns": 8, "requestId": "req-001" },
  "seq": 1
}
```

### 1.3 Agent 调用 writePlan 工具

```json
<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-plan-001",
    "name": "writePlan",
    "arguments": "{\"title\":\"搭建 Spring Boot 项目\",\"stepsJson\":\"[{\\\"title\\\":\\\"初始化项目结构\\\",\\\"description\\\":\\\"使用 Spring Initializr 创建项目骨架，添加 web、jpa、flyway 依赖\\\"},{\\\"title\\\":\\\"配置数据源和 Flyway\\\",\\\"description\\\":\\\"编写 application.yml 数据源配置和首个迁移脚本\\\"},{\\\"title\\\":\\\"编写示例 Controller 和 Entity\\\",\\\"description\\\":\\\"创建 HelloController 和 User 实体验证整体链路\\\"}]\"}",
    "turn": 1,
    "requestId": "req-001"
  },
  "seq": 2
}
```

### 1.4 writePlan 工具返回结果（DB 创建成功）

```json
<- {
  "type": "event",
  "event": "agent.tool_result",
  "payload": {
    "toolCallId": "tc-plan-001",
    "name": "writePlan",
    "result": "{\"planId\":42,\"status\":\"draft\",\"message\":\"Plan created\"}",
    "success": true,
    "turn": 1,
    "requestId": "req-001"
  },
  "seq": 3
}
```

### 1.5 plan.created 事件推送到前端

```json
<- {
  "type": "event",
  "event": "plan.created",
  "payload": {
    "planId": 42,
    "title": "搭建 Spring Boot 项目",
    "steps": [
      { "index": 0, "title": "初始化项目结构", "description": "使用 Spring Initializr 创建项目骨架，添加 web、jpa、flyway 依赖" },
      { "index": 1, "title": "配置数据源和 Flyway", "description": "编写 application.yml 数据源配置和首个迁移脚本" },
      { "index": 2, "title": "编写示例 Controller 和 Entity", "description": "创建 HelloController 和 User 实体验证整体链路" }
    ],
    "requestId": "req-001"
  },
  "seq": 4
}
```

### 1.6 plan.awaiting_approval 等待用户审批

```json
<- {
  "type": "event",
  "event": "plan.awaiting_approval",
  "payload": { "planId": 42, "requestId": "req-001" },
  "seq": 5
}
```

### 1.7 Agent 输出提示文本并结束

```json
<- {
  "type": "event",
  "event": "agent.chunk",
  "payload": { "text": "我已经为你制定了一个 3 步计划，请在左侧面板查看并审批。", "requestId": "req-001" },
  "seq": 6
}

<- {
  "type": "event",
  "event": "agent.done",
  "payload": { "text": "我已经为你制定了一个 3 步计划，请在左侧面板查看并审批。", "totalTurns": 1, "requestId": "req-001" },
  "seq": 7
}

<- {
  "type": "response",
  "id": "req-001",
  "ok": true,
  "payload": { "text": "我已经为你制定了一个 3 步计划，请在左侧面板查看并审批。" },
  "error": null
}
```

### 1.8 用户审批通过

```json
-> {
  "type": "request",
  "id": "req-002",
  "method": "plan.approve",
  "params": { "planId": 42, "approved": true }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 42, "status": "approved" },
  "seq": 8
}

<- {
  "type": "response",
  "id": "req-002",
  "ok": true,
  "payload": { "status": "approved" },
  "error": null
}
```

### 1.9 用户恢复（启动执行）

```json
-> {
  "type": "request",
  "id": "req-003",
  "method": "plan.resume",
  "params": { "planId": 42 }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 42, "status": "executing" },
  "seq": 9
}

<- {
  "type": "response",
  "id": "req-003",
  "ok": true,
  "payload": { "status": "executing" },
  "error": null
}
```

### 1.10 用户发送消息触发步骤 0 执行（plan 上下文注入）

```json
-> {
  "type": "request",
  "id": "req-004",
  "method": "conversation.message",
  "params": {
    "text": "开始执行计划",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "user-abc"
  }
}
```

Agent 系统提示中自动注入 `planContext`：

```
## PLAN EXECUTION CONTEXT

你正在执行一个已审批的计划。

### 当前步骤 (1/3):
**初始化项目结构**
使用 Spring Initializr 创建项目骨架，添加 web、jpa、flyway 依赖

### 待执行的步骤:
- [ ] Step 1: 配置数据源和 Flyway
- [ ] Step 2: 编写示例 Controller 和 Entity

请专注于完成当前步骤。完成后调用 `updatePlan` 的 `markStep` 标记完成。
如果发现需要调整计划，可以使用 addStep / removeStep / completePlan。
```

### 1.11 Agent 标记步骤 0 为 in_progress，然后完成

```json
<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-update-001",
    "name": "updatePlan",
    "arguments": "{\"planId\":42,\"action\":\"markStep\",\"stepIndex\":0,\"status\":\"in_progress\"}",
    "turn": 1,
    "requestId": "req-004"
  },
  "seq": 10
}

<- {
  "type": "event",
  "event": "agent.tool_result",
  "payload": {
    "toolCallId": "tc-update-001",
    "name": "updatePlan",
    "result": "{\"stepIndex\":0,\"status\":\"in_progress\",\"message\":\"Step marked\"}",
    "success": true,
    "turn": 1,
    "requestId": "req-004"
  },
  "seq": 11
}
```

（Agent 执行实际工作后标记完成）

```json
<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-update-002",
    "name": "updatePlan",
    "arguments": "{\"planId\":42,\"action\":\"markStep\",\"stepIndex\":0,\"status\":\"completed\",\"resultSummary\":\"项目骨架已创建，包含 spring-boot-starter-web、spring-boot-starter-data-jpa、flyway-core 依赖\"}",
    "turn": 2,
    "requestId": "req-004"
  },
  "seq": 12
}

<- {
  "type": "event",
  "event": "agent.tool_result",
  "payload": {
    "toolCallId": "tc-update-002",
    "name": "updatePlan",
    "result": "{\"stepIndex\":0,\"status\":\"completed\",\"message\":\"Step marked\"}",
    "success": true,
    "turn": 2,
    "requestId": "req-004"
  },
  "seq": 13
}
```

### 1.12 类似地完成步骤 1 和步骤 2（省略重复 tool_call/tool_result）

步骤 1 — `markStep(stepIndex=1, status=in_progress)` → `markStep(stepIndex=1, status=completed, resultSummary="数据源和 Flyway V1 迁移脚本已配置完成")`

步骤 2 — `markStep(stepIndex=2, status=in_progress)` → `markStep(stepIndex=2, status=completed, resultSummary="HelloController 和 User 实体已创建并通过编译")`

### 1.13 Agent 调用 completePlan 标记计划完成

```json
<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-update-007",
    "name": "updatePlan",
    "arguments": "{\"planId\":42,\"action\":\"completePlan\",\"resultSummary\":\"Spring Boot 项目搭建完成，包含 Web+JPA+Flyway 全栈配置\"}",
    "turn": 3,
    "requestId": "req-004"
  },
  "seq": 20
}

<- {
  "type": "event",
  "event": "agent.tool_result",
  "payload": {
    "toolCallId": "tc-update-007",
    "name": "updatePlan",
    "result": "{\"planId\":42,\"status\":\"completed\",\"message\":\"Plan completed\"}",
    "success": true,
    "turn": 3,
    "requestId": "req-004"
  },
  "seq": 21
}
```

### 1.14 plan.completed 事件

```json
<- {
  "type": "event",
  "event": "plan.completed",
  "payload": { "planId": 42, "status": "completed", "requestId": "req-004" },
  "seq": 22
}
```

---

## 对话 2：审批时修改步骤（edit / add / remove）

场景：Agent 生成 3 步计划，用户在审批前编辑第 1 步标题、追加第 4 步、删除第 2 步。

### 2.1 前置：plan.created 已收到（同对话 1 的 1.1~1.6）

假设 `planId = 50`，3 个步骤：
- Step 0: "需求分析"
- Step 1: "数据库设计"
- Step 2: "API 开发"

### 2.2 用户审批并附带修改

```json
-> {
  "type": "request",
  "id": "req-010",
  "method": "plan.approve",
  "params": {
    "planId": 50,
    "approved": true,
    "modifications": [
      {
        "type": "edit",
        "stepIndex": 1,
        "title": "数据库设计与建表脚本",
        "description": "包含 ER 图设计和 Flyway 迁移脚本"
      },
      {
        "type": "add",
        "stepIndex": 2,
        "title": "编写单元测试",
        "description": "对核心 Service 层编写 JUnit 5 测试"
      },
      {
        "type": "remove",
        "stepIndex": 2
      }
    ]
  }
}
```

**处理顺序**（`PlanService.approvePlan` 逐条应用）：
1. **edit**: 修改 step[1] 标题为"数据库设计与建表脚本"，添加描述
2. **add**: 在 step[2] 之后插入新步骤"编写单元测试"（此时共 4 步）
3. **remove**: 删除 step[2]（原"API 开发"），重建索引

最终步骤：
- Step 0: "需求分析"
- Step 1: "数据库设计与建表脚本"
- Step 2: "编写单元测试"

### 2.3 服务器响应

```json
<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 50, "status": "approved" },
  "seq": 30
}

<- {
  "type": "response",
  "id": "req-010",
  "ok": true,
  "payload": { "status": "approved" },
  "error": null
}
```

### 2.4 用户拒绝审批（对比场景）

```json
-> {
  "type": "request",
  "id": "req-011",
  "method": "plan.approve",
  "params": { "planId": 51, "approved": false }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 51, "status": "cancelled" },
  "seq": 31
}

<- {
  "type": "response",
  "id": "req-011",
  "ok": true,
  "payload": { "status": "cancelled" },
  "error": null
}
```

---

## 对话 3：执行中暂停与恢复

场景：3 步计划正在执行步骤 1 时，用户主动暂停，稍后恢复。

### 3.1 前置：计划 planId=60 已处于 executing 状态，步骤 0 已完成

### 3.2 Agent 正在执行步骤 1

```json
<- {
  "type": "event",
  "event": "plan.step_start",
  "payload": { "planId": 60, "stepIndex": 1, "title": "实现用户认证模块", "requestId": "req-020" },
  "seq": 40
}
```

### 3.3 用户暂停计划

```json
-> {
  "type": "request",
  "id": "req-021",
  "method": "plan.pause",
  "params": { "planId": 60 }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 60, "status": "paused" },
  "seq": 41
}

<- {
  "type": "response",
  "id": "req-021",
  "ok": true,
  "payload": { "status": "paused" },
  "error": null
}
```

### 3.4 用户在暂停期间修改步骤 2 的描述

```json
-> {
  "type": "request",
  "id": "req-022",
  "method": "plan.modify_step",
  "params": {
    "planId": 60,
    "stepIndex": 2,
    "title": "集成 OAuth2 授权",
    "description": "使用 Spring Security OAuth2 替代原有的 Session 方案"
  }
}

<- {
  "type": "response",
  "id": "req-022",
  "ok": true,
  "payload": { "status": "modified" },
  "error": null
}
```

> **注意**: `plan.modify_step` 只返回 ResponseFrame，不推送 `plan.adjusted` 事件。前端需自行更新本地状态。

### 3.5 用户恢复执行

```json
-> {
  "type": "request",
  "id": "req-023",
  "method": "plan.resume",
  "params": { "planId": 60 }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 60, "status": "executing" },
  "seq": 42
}

<- {
  "type": "response",
  "id": "req-023",
  "ok": true,
  "payload": { "status": "executing" },
  "error": null
}
```

### 3.6 用户发消息继续执行（此时 planContext 包含已完成步骤信息）

```json
-> {
  "type": "request",
  "id": "req-024",
  "method": "conversation.message",
  "params": {
    "text": "继续执行",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "user-abc"
  }
}
```

注入的 `planContext` 此时包含：

```
## PLAN EXECUTION CONTEXT

你正在执行一个已审批的计划。

### 已完成的步骤:
- [x] Step 0: 项目初始化 → 项目骨架已创建

### 当前步骤 (2/3):
**实现用户认证模块**
实现基于 JWT 的用户认证

### 待执行的步骤:
- [ ] Step 2: 集成 OAuth2 授权

请专注于完成当前步骤。完成后调用 `updatePlan` 的 `markStep` 标记完成。
如果发现需要调整计划，可以使用 addStep / removeStep / completePlan。
```

---

## 对话 4：步骤失败 → 自动暂停 → 跳过 → 恢复

场景：执行步骤 1 失败，计划自动暂停，用户选择跳过失败步骤后恢复。

### 4.1 前置：planId=70，executing 状态，步骤 0 已完成

### 4.2 Agent 标记步骤 1 为 failed

```json
<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-fail-001",
    "name": "updatePlan",
    "arguments": "{\"planId\":70,\"action\":\"markStep\",\"stepIndex\":1,\"status\":\"failed\",\"resultSummary\":\"外部 API 返回 503，无法完成数据同步\"}",
    "turn": 2,
    "requestId": "req-030"
  },
  "seq": 50
}

<- {
  "type": "event",
  "event": "agent.tool_result",
  "payload": {
    "toolCallId": "tc-fail-001",
    "name": "updatePlan",
    "result": "{\"stepIndex\":1,\"status\":\"failed\",\"message\":\"Step marked\"}",
    "success": true,
    "turn": 2,
    "requestId": "req-030"
  },
  "seq": 51
}
```

### 4.3 PlanService.markStep 内部自动触发 pausePlan（步骤 failed 时）

`plan.step_done` 和 `plan.status_changed` 事件推送：

```json
<- {
  "type": "event",
  "event": "plan.step_done",
  "payload": {
    "planId": 70,
    "stepIndex": 1,
    "status": "failed",
    "resultSummary": "外部 API 返回 503，无法完成数据同步",
    "requestId": "req-030"
  },
  "seq": 52
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 70, "status": "paused" },
  "seq": 53
}
```

> **注意**: 当前实现中 `markStep(failed)` 会调用 `pausePlan()`，但 `plan.status_changed` 事件的推送取决于是否有 `AgentEvent.PlanStatusChanged` 被 emit（目前该路径未连接）。上述为设计预期行为。

### 4.4 前端 PlanView 显示失败横幅，用户跳过该步骤

```json
-> {
  "type": "request",
  "id": "req-031",
  "method": "plan.skip_step",
  "params": { "planId": 70, "stepIndex": 1 }
}

<- {
  "type": "event",
  "event": "plan.step_done",
  "payload": {
    "planId": 70,
    "stepIndex": 1,
    "status": "skipped",
    "resultSummary": "用户跳过"
  },
  "seq": 54
}

<- {
  "type": "response",
  "id": "req-031",
  "ok": true,
  "payload": { "status": "skipped" },
  "error": null
}
```

### 4.5 用户恢复执行

```json
-> {
  "type": "request",
  "id": "req-032",
  "method": "plan.resume",
  "params": { "planId": 70 }
}

<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 70, "status": "executing" },
  "seq": 55
}

<- {
  "type": "response",
  "id": "req-032",
  "ok": true,
  "payload": { "status": "executing" },
  "error": null
}
```

### 4.6 继续执行步骤 2

```json
-> {
  "type": "request",
  "id": "req-033",
  "method": "conversation.message",
  "params": { "text": "继续下一步", "channelId": "webchat", "contextType": "dm", "contextId": "user-abc" }
}
```

此时 `planContext` 中步骤 1 显示为：
```
### 已完成的步骤:
- [x] Step 0: 数据采集 → 数据采集完成
```
（步骤 1 为 skipped，不出现在已完成和待执行中）

---

## 对话 5：用户主动取消计划

场景：计划执行中用户决定取消整个计划。

### 5.1 前置：planId=80，executing 状态

### 5.2 用户取消

```json
-> {
  "type": "request",
  "id": "req-040",
  "method": "plan.cancel",
  "params": { "planId": 80 }
}
```

### 5.3 服务端处理

`PlanService.cancelPlan(80)`:
- 所有 `pending` / `in_progress` 步骤标记为 `skipped`，`resultSummary = "计划已取消"`
- 计划状态设为 `cancelled`

```json
<- {
  "type": "event",
  "event": "plan.status_changed",
  "payload": { "planId": 80, "status": "cancelled" },
  "seq": 60
}

<- {
  "type": "response",
  "id": "req-040",
  "ok": true,
  "payload": { "status": "cancelled" },
  "error": null
}
```

### 5.4 取消后用户发送新消息（无 plan 上下文）

```json
-> {
  "type": "request",
  "id": "req-041",
  "method": "conversation.message",
  "params": { "text": "换个方案，我想用 Python 来做", "channelId": "webchat", "contextType": "dm", "contextId": "user-abc" }
}
```

此时 `getActivePlan()` 查询 `status IN ('draft','approved','executing','paused')`，`cancelled` 不匹配，因此：
- `planExecuting = false`
- 不注入 `planContext`
- 使用 `getChatHistory` 而非 `getPlanHistory`

---

## 对话 6：/plan 强制 Plan 模式

场景：用户通过 `/plan` 命令强制 Agent 以 Plan 模式处理任务。

### 6.1 用户发送 /plan 命令

```json
-> {
  "type": "request",
  "id": "req-050",
  "method": "conversation.message",
  "params": {
    "text": "/plan 重构用户模块，拆分为独立微服务",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "user-abc"
  }
}
```

### 6.2 CommandHandler 识别 /plan 命令

`CommandHandler.handlePlan` 返回带 `forcePlan: true` 的响应：

```json
<- {
  "type": "response",
  "id": "req-050",
  "ok": true,
  "payload": {
    "text": "重构用户模块，拆分为独立微服务",
    "command": "plan",
    "forcePlan": true
  },
  "error": null
}
```

### 6.3 前端收到 forcePlan 后，将文本作为普通消息重新发送

前端检测到 `command: "plan"` + `forcePlan: true`，将提取的文本以 `conversation.message` 重新发送（此为前端逻辑，需前端实现）：

```json
-> {
  "type": "request",
  "id": "req-051",
  "method": "conversation.message",
  "params": {
    "text": "重构用户模块，拆分为独立微服务",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "user-abc",
    "forcePlan": true
  }
}
```

> **注意**: 当前 `MessagePipeline.processMessageStreaming` 中 `forcePlan` 固定为 `false`。若要支持 `/plan` 强制模式，需在 pipeline 中从 params 读取 `forcePlan` 并传递给 `AgentRunRequest`。这是一个已知的待实现功能。

### 6.4 Agent 系统提示中包含强制 Plan 指令

当 `forcePlan = true` 时，`AgentRuntime.buildSystemPrompt` 中的 `<plan_system>` 块会追加额外指令，强制 Agent 必须先调用 `writePlan` 创建计划：

```json
<- {
  "type": "event",
  "event": "agent.turn_start",
  "payload": { "turn": 1, "maxTurns": 8, "requestId": "req-051" },
  "seq": 70
}

<- {
  "type": "event",
  "event": "agent.tool_call",
  "payload": {
    "toolCallId": "tc-force-001",
    "name": "writePlan",
    "arguments": "{\"title\":\"重构用户模块为独立微服务\",\"stepsJson\":\"[{\\\"title\\\":\\\"分析现有用户模块边界\\\",\\\"description\\\":\\\"梳理用户模块与其他模块的依赖关系和 API 边界\\\"},{\\\"title\\\":\\\"设计微服务接口契约\\\",\\\"description\\\":\\\"定义 gRPC/REST API 接口、事件协议和数据迁移方案\\\"},{\\\"title\\\":\\\"抽取独立服务\\\",\\\"description\\\":\\\"创建新的微服务项目，迁移代码并配置服务发现\\\"},{\\\"title\\\":\\\"集成测试与灰度上线\\\",\\\"description\\\":\\\"编写集成测试，配置灰度发布策略\\\"}]\"}",
    "turn": 1,
    "requestId": "req-051"
  },
  "seq": 71
}
```

后续流程与对话 1 相同（tool_result → plan.created → plan.awaiting_approval → agent.done）。

---

## 附录：前端 planStore 状态变化汇总

| WebSocket 事件 | planStore handler | 状态变化 |
|---|---|---|
| `plan.created` | `handlePlanCreated` | `plan = { planId, title, status: "draft", steps: [...pending] }` |
| `plan.awaiting_approval` | `setAwaitingApproval` | `plan.status = "draft"`（确认态） |
| `plan.status_changed` | `handlePlanStatusChanged` | `plan.status = payload.status` |
| `plan.step_start` | `handleStepStart` | `steps[stepIndex].status = "in_progress"` |
| `plan.step_done` | `handleStepDone` | `steps[stepIndex].status = payload.status`, `resultSummary` 更新 |
| `plan.adjusted` | `handlePlanAdjusted` | 重建步骤列表，按 index 合并已有状态 |
| `plan.completed` | `handlePlanCompleted` | `plan.status = payload.status` |

## 附录：已知实现缺口

1. **AgentEvent.Plan\* 未被 emit**: `AgentRuntime` 中没有构造 `PlanCreated` / `PlanStepStart` 等事件的代码。当前 plan 工具调用只产生 `agent.tool_call` / `agent.tool_result`，`plan.*` 事件路径在 `mapAgentEvent` 中已实现但从未触发。
2. **plan.modify_step 无事件推送**: 只返回 `ResponseFrame`，不发 `plan.adjusted`，前端需自行同步。
3. **forcePlan 未从 params 透传**: `processMessageStreaming` 中 `forcePlan` 硬编码为 `false`，`/plan` 命令的 `forcePlan` 标记未被消费。
4. **PlanStepCard 的 onRemove 未连接**: `PlanView` 传给 `PlanStepCard` 时未提供 `onRemove` 回调。
