# 多 Agent 协作功能测试文档

## 测试概述

本文档覆盖多 Agent 协作的三种模式：**委派 (Delegation)**、**移交 (Handoff)**、**并行 (Parallel)**，以及相关的配置管理和前端可视化功能。

---

## 1. 数据库迁移验证

### 1.1 V21 迁移字段检查

**目的**：验证 `agent` 表新增的 3 个字段。

**步骤**：
```sql
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'javaclaw' AND TABLE_NAME = 'agent'
  AND COLUMN_NAME IN ('can_delegate', 'delegate_agents', 'goal');
```

**预期结果**：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| can_delegate | tinyint | 0 | 是否允许委派 |
| delegate_agents | text | NULL | 可委派的 Agent 名称列表 (JSON 数组) |
| goal | text | NULL | Agent 目标描述 |

---

## 2. API 接口测试

### 2.1 Agent 列表包含委派字段

```bash
curl -s http://localhost:3007/api/agents | python3 -m json.tool
```

**预期**：每个 Agent 包含 `canDelegate` (boolean) 和 `goal` (string|null) 字段。

### 2.2 Agent 详情包含委派字段

```bash
curl -s http://localhost:3007/api/agent/{name} | python3 -m json.tool
```

**预期**：包含 `canDelegate`、`delegateAgents`、`goal` 三个新字段。

### 2.3 更新委派配置

```bash
curl -s -X PUT http://localhost:3007/api/agent/{name} \
  -H 'Content-Type: application/json' \
  -d '{
    "canDelegate": true,
    "delegateAgents": "[\"worker1\",\"worker2\"]",
    "goal": "主协调者"
  }'
```

**预期**：返回 `{"success": true}`，再次 GET 确认数据已持久化。

### 2.4 工具列表包含委派工具

```bash
curl -s http://localhost:3007/api/tools | python3 -c "
import json, sys
data = json.load(sys.stdin)
delegation_tools = [t for t in data['tools'] if t['group'] == 'DELEGATION']
for t in delegation_tools:
    print(f'{t[\"name\"]}: {t[\"description\"][:60]}')
"
```

**预期**：返回 3 个工具：
- `delegateAgent` — 单 Agent 委派
- `handoffToAgent` — 控制权移交
- `delegateAgentsParallel` — 并行委派

---

## 3. 委派 (Delegation) 流程测试

### 3.1 前置条件

1. 至少 2 个 Agent（如 `supervisor` 和 `worker`）
2. `supervisor` 的 `canDelegate=true`，`delegateAgents=["worker"]`
3. `worker` 有正确的 `goal` 描述

### 3.2 基本委派流程

**发送消息**（通过 WebSocket）：
```json
{
  "type": "request",
  "id": "test-1",
  "method": "conversation.message",
  "params": {
    "text": "请委派给 worker 来完成一个简单任务",
    "agentName": "supervisor"
  }
}
```

**预期事件序列**：
1. `agent.turn_start` — Supervisor 开始推理
2. `agent.tool_call` — name=`delegateAgent`，arguments 包含 agentName 和 task
3. `workflow.delegation_start` — workerAgent、task、delegationId
4. `workflow.delegation_progress` — Worker 的 turn_start/tool_call/tool_result 事件
5. `workflow.delegation_result` — success、turnsUsed、durationMs、result
6. `agent.tool_result` — delegateAgent 工具结果（Worker 的输出）
7. `agent.done` — Supervisor 综合 Worker 结果后的最终回复

### 3.3 委派失败处理

**场景**：Worker Agent 不存在或 API 不可用。

**预期**：
- `workflow.delegation_result` 中 `success=false`
- Supervisor 收到失败结果后，应降级处理（自行回答或告知用户）

### 3.4 嵌套深度限制

**场景**：Worker 也配置了 `canDelegate=true`，尝试嵌套委派。

**预期**：
- 默认 `maxNestingDepth=2`，超过深度时委派被拒绝
- 返回 "Delegation limit reached" 错误

### 3.5 委派次数限制

**场景**：单次会话中连续委派超过 10 次。

**预期**：
- 默认 `maxDelegations=10`
- 超出限制后，delegateAgent 工具返回限制信息

---

## 4. 移交 (Handoff) 流程测试

### 4.1 基本移交流程

**发送消息**：
```json
{
  "params": {
    "text": "这个问题你处理不了，请移交给更合适的 agent",
    "agentName": "supervisor"
  }
}
```

**预期事件序列**：
1. `agent.tool_call` — name=`handoffToAgent`
2. `workflow.handoff` — fromAgent、toAgent、reason、contextSummary
3. 当前 Agent loop 终止
4. MessagePipeline 启动目标 Agent 的新 loop
5. 目标 Agent 的 `agent.turn_start`/`agent.chunk`/`agent.done` 事件

### 4.2 Handoff 上下文传递

**验证**：目标 Agent 收到的消息中包含：
- 来源 Agent 名称
- Handoff 原因
- 对话上下文摘要

---

## 5. 并行 (Parallel) 流程测试

### 5.1 前置条件

- Supervisor 的 `delegateAgents` 包含多个 Worker
- 例如 `delegateAgents=["worker1","worker2","worker3"]`

### 5.2 基本并行流程

**发送消息**：
```json
{
  "params": {
    "text": "请同时让 worker1 和 worker2 分别完成不同的任务",
    "agentName": "supervisor"
  }
}
```

**预期事件序列**：
1. `agent.tool_call` — name=`delegateAgentsParallel`
2. `workflow.parallel_start` — parallelGroupId、tasks 列表
3. `workflow.parallel_progress` — 各 Worker 的进度事件
4. `workflow.parallel_result` — 所有 Worker 的结果汇总
5. `agent.tool_result` — 并行执行的汇总结果

### 5.3 并行数量限制

**场景**：同时委派超过 `maxParallel`（默认 4）个 Agent。

**预期**：
- Reactor 的 `flatMap(fn, maxParallel)` 控制并发度
- 超出限制的任务排队等待

---

## 6. 工具条件注入测试

### 6.1 canDelegate=false 时不注入委派工具

**步骤**：
1. 设置 Agent 的 `canDelegate=false`
2. 发送任何消息
3. 检查 LLM 请求中的 tools 列表

**预期**：tools 列表中不包含 `delegateAgent`、`handoffToAgent`、`delegateAgentsParallel`。

### 6.2 canDelegate=true 时注入委派工具

**步骤**：
1. 设置 Agent 的 `canDelegate=true`，并配置 `delegateAgents`
2. 发送任何消息

**预期**：
- tools 列表中包含 3 个委派工具
- System prompt 中包含 "Available Delegate Agents" 部分

---

## 7. 前端组件测试

### 7.1 委派配置面板

**路径**：Agent 配置 Modal → "委派协作" Tab

**测试项**：
- [ ] 切换"允许委派任务"开关
- [ ] 输入 Agent 目标描述
- [ ] 勾选/取消可委派的目标 Agent
- [ ] 保存配置后刷新验证

### 7.2 委派卡片 (DelegationCard)

**触发**：Agent 执行委派操作时

**验证**：
- [ ] 卡片显示 Worker 名称和任务描述
- [ ] 状态指示（running/completed/failed）颜色正确
- [ ] 展开后显示 Worker 的工具调用列表
- [ ] 完成后显示结果预览、耗时、turns 数

### 7.3 并行组 (ParallelGroup)

**触发**：Agent 执行并行委派时

**验证**：
- [ ] 显示并行任务数量和完成进度
- [ ] 各 Worker 状态指示器颜色正确
- [ ] 全部完成后整体颜色变为绿色

### 7.4 Handoff 指示器 (HandoffIndicator)

**触发**：Agent 执行 handoff 时

**验证**：
- [ ] 显示 fromAgent → toAgent
- [ ] 显示移交原因

### 7.5 工作流时间线 (WorkflowTimeline)

**验证**：
- [ ] 在消息气泡中正确渲染多个工作流条目
- [ ] 委派、并行、移交三种类型正确展示
- [ ] 嵌套的工具调用可折叠展开

---

## 8. WebSocket 事件测试

### 8.1 新事件类型清单

| 事件类型 | 触发时机 | 关键字段 |
|---------|---------|---------|
| `workflow.delegation_start` | 委派开始 | workerAgent, task, delegationId |
| `workflow.delegation_progress` | 委派进度 | workerAgent, delegationId, eventType |
| `workflow.delegation_result` | 委派完成 | success, turnsUsed, durationMs, result |
| `workflow.handoff` | 控制权移交 | fromAgent, toAgent, reason, contextSummary |
| `workflow.parallel_start` | 并行开始 | parallelGroupId, tasks |
| `workflow.parallel_progress` | 并行进度 | parallelGroupId, agentName, eventType |
| `workflow.parallel_result` | 并行完成 | parallelGroupId, results |

### 8.2 事件完整性验证

**Python 脚本**：
```python
import asyncio, json, websockets, uuid

async def test():
    async with websockets.connect('ws://localhost:3007/ws?token=') as ws:
        await ws.recv()  # welcome
        msg = {
            'type': 'request',
            'id': str(uuid.uuid4()),
            'method': 'conversation.message',
            'params': {
                'text': '请使用 delegateAgent 委派给 worker 完成任务',
                'agentName': 'supervisor'
            }
        }
        await ws.send(json.dumps(msg))
        
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=120)
            data = json.loads(raw)
            event = data.get('event', '')
            if 'workflow.' in event:
                print(f'{event}: {json.dumps(data["payload"], ensure_ascii=False)[:200]}')
            if event == 'agent.done' or data.get('type') == 'response':
                break

asyncio.run(test())
```

---

## 9. 安全与限制测试

| 测试项 | 默认值 | 验证方法 |
|--------|--------|---------|
| 嵌套深度限制 | maxNestingDepth=2 | Worker 嵌套委派超过 2 层 |
| 并行上限 | maxParallel=4 | 同时委派超过 4 个 Agent |
| 单次最大委派次数 | maxDelegations=10 | 连续委派超过 10 次 |
| 结果最大长度 | 32KB | Worker 返回超长结果 |
| 非委派 Agent 无委派工具 | canDelegate=false | 检查工具列表 |

---

## 10. 已验证测试结果

### 10.1 2026-05-15 集成测试结果

| 测试项 | 结果 | 备注 |
|--------|------|------|
| V21 迁移执行 | ✅ 通过 | Flyway 自动执行 |
| Agent 列表含 canDelegate/goal | ✅ 通过 | GET /api/agents |
| 委派配置 CRUD | ✅ 通过 | PUT/GET /api/agent/{name} |
| 3 个委派工具注册 | ✅ 通过 | GET /api/tools |
| 工具归属"委派协作"组 | ✅ 通过 | group=DELEGATION |
| Java 后端编译 | ✅ 通过 | mvn clean compile |
| TypeScript 类型检查 | ✅ 通过 | npx tsc --noEmit |
| 前端 production build | ✅ 通过 | npx vite build |
| WebSocket 委派事件流 | ✅ 通过 | delegation_start → progress → result |
| Supervisor 降级处理 | ✅ 通过 | Worker API 404 时自主回答 |
| canDelegate=false 过滤工具 | ✅ 通过 | 代码级验证 |

### 10.2 待环境就绪后验证

- [ ] Worker Agent API 正常时的完整委派成功流程
- [ ] Handoff 完整链路（需要 2 个可用 Agent）
- [ ] 并行委派完整链路（需要多个可用 Agent）
- [ ] 前端浏览器端可视化验证
