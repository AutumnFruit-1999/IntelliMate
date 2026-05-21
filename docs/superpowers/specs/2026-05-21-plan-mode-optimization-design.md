# Plan Mode 优化设计规格

**状态：** 待审阅
**日期：** 2026-05-21
**范围：** 9 项优化，覆盖 Agent Runtime、PlanService、前端 PlanPanel/planStore、系统 Prompt

---

## 背景

Plan Mode 是 IntelliMate 的核心功能之一，允许 Agent 通过 `writePlan` / `updatePlan` 工具创建和执行多步骤计划。经过生产使用观察，发现以下性能、体验和健壮性问题。

### 现有架构简述

- **Agent 层：** `WritePlanTool` / `UpdatePlanTool` + `PlanStepTracker`（自动步骤推进）+ `plan-system.md` / `plan-execution-context.md` 提示词
- **Gateway 层：** `PlanService`（状态机）+ `PlanOperationsImpl`（SPI 桥接）+ `MessagePipeline`（事件映射 + 后执行同步）
- **前端：** `planStore`（Zustand）+ `PlanPanel`（右侧面板）+ `PlanStepCard` + WebSocket 事件处理

---

## 优化 1：放宽 markStep(in_progress) 独占回合限制

### 问题

`plan-execution-context.md` 要求：

> 禁止在未调用 markStep(status="in_progress") 之前执行该步骤的任何其他工具
> 禁止将 markStep(status="in_progress") 与其他工具在同一轮并行调用

这导致每个步骤执行前需要单独一个 LLM 回合仅调用 `markStep`，然后下一个回合才执行实际工具。10 步计划至少 20 个 LLM 回合，额外 ~50% 的 token 和延迟开销。

`PlanStepTracker` 的存在本身就证明 LLM 频繁违反此约束——系统已经有 auto-start 补救机制。

### 设计

**方案 A（推荐）：放宽 prompt 约束**

修改 `plan-execution-context.md`，允许 `markStep(in_progress)` 与同步骤的其他工具在同一回合并行调用：

```markdown
1. **开始**：在执行该步骤的工具时，必须先调用或同时调用：
   `updatePlan(planId=<Plan ID>, action="markStep", stepIndex=N, status="in_progress")`
   可以与该步骤的第一个工具在同一轮并行调用。
```

**对 `extractPlanEvents` 的影响：** 无。该方法已经能从同一回合的多个工具调用中分别提取事件。`PlanStepTracker.filterDuplicatePlanEvents` 也已处理重复事件。

**方案 B：完全依赖 PlanStepTracker**

移除 `markStep(in_progress)` 的强制要求，完全由 `PlanStepTracker` 自动检测非 plan 工具调用时推进步骤。前端仍通过 `plan.step_start` 事件更新 UI。

**方案 B 的风险：** LLM 可能跳步执行（如先做步骤 3 再做步骤 1），`PlanStepTracker` 默认按顺序推进，无法处理乱序。

**决策：采用方案 A。** 保留 `markStep` 调用以保持步骤意图明确，但放宽并行限制。

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/resources/prompts/plan-execution-context.md` | 放宽并行限制描述 |

### 预期效果

- 步骤执行从 2 回合降为 1 回合
- 10 步计划从 ~20 回合降为 ~12 回合（10 步 + 1 创建 + 1 完成）
- Token 消耗减少 ~30-40%

---

## 优化 2：步骤批量保存改为并发或批量 INSERT

### 问题

`PlanService.createPlan` 使用 `concatMap` 串行保存每个步骤：

```java
.concatMap(step -> planStepRepository.save(step))
```

10 个步骤 = 10 次串行 DB 往返 + 1 次验证查询，总计 ~11 次 DB 调用。

### 设计

将 `concatMap` 改为 `Flux.merge` 或 `saveAll`（如果 repository 支持）。保留验证查询确保数据完整性。

```java
// 改为并发保存
.flatMap(step -> planStepRepository.save(step), 4) // 最大并发 4
```

或使用 `saveAll`：

```java
return planStepRepository.saveAll(stepEntities)
        .collectList()
        .flatMap(savedSteps -> {
            if (savedSteps.size() != expectedCount) {
                return Mono.error(new IllegalStateException("Step count mismatch"));
            }
            return Mono.just(savedPlan);
        });
```

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java` | `createPlan` 方法改为并发保存 |

### 预期效果

- 创建计划延迟从 ~N×RTT 降为 ~1-2×RTT

---

## 优化 3：去掉执行期间的 10 秒轮询

### 问题

前端在 `useWebSocket.ts` 中，当计划状态为 `executing` 时每 10 秒调用 `syncFromServer(planId)`。但所有步骤状态变化已通过 WebSocket 实时推送（`plan.step_start`、`plan.step_done`、`plan.status_changed`）。轮询产生不必要的 HTTP 请求和数据库查询。

### 设计

将固定 10 秒轮询改为**事件驱动 + 超时兜底**：

1. 正常执行期间不轮询
2. WebSocket 重连后立即 `syncFromServer` 一次
3. 如果超过 60 秒未收到任何 `plan.*` WebSocket 事件，触发一次 `syncFromServer` 作为健康检查
4. `agent.done` 事件后触发一次最终同步

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-web/src/hooks/useWebSocket.ts` | 移除固定 10s `setInterval`，改为超时兜底 |
| `intellimate-web/src/stores/planStore.ts` | 新增 `lastEventTimestamp` 字段 |

### 预期效果

- 执行期间 HTTP 请求数从 N（每 10 秒一次）降为 ~2-3 次

---

## 优化 4：审批-执行合并为一站式 RPC

### 问题

"批准并执行" 按钮触发 3 个串行操作：

1. `plan.approve` → 等待响应
2. `plan.resume` → 等待响应
3. `sendMessage("开始执行计划")` → 触发 Agent

3 次 WebSocket 往返增加了延迟（约 100-300ms × 3），用户体验上有可感知的卡顿。

### 设计

新增 `plan.approve_and_execute` 后端方法，在 `MessagePipeline.processPlanRequest` 中处理：

```java
case "plan.approve_and_execute" -> {
    Long planId = ((Number) params.get("planId")).longValue();
    return planService.approvePlan(planId, true, null)
            .flatMap(plan -> planService.resumePlan(planId).thenReturn(plan))
            .flatMapMany(plan -> {
                GatewayFrame statusFrame = new EventFrame("plan.status_changed",
                        Map.of("planId", planId, "status", "executing"), seqGen.incrementAndGet());
                // 直接触发 Agent 执行
                return Flux.just(statusFrame)
                        .concatWith(processMessageStreaming(session, "开始执行计划", requestId, wsSessionId, false));
            });
}
```

前端 `PlanPanel.tsx` 简化为：

```tsx
const handleApproveAndExecute = useCallback(async () => {
    const res = await onSendPlanActionAndWait(createPlanApproveAndExecute(plan.planId));
    // 完成
}, [plan, onSendPlanActionAndWait]);
```

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java` | `processPlanRequest` 新增 case |
| `intellimate-web/src/lib/protocol.ts` | 新增 `createPlanApproveAndExecute` |
| `intellimate-web/src/components/PlanPanel.tsx` | 简化 `handleApproveAndExecute` |

### 预期效果

- "批准并执行" 从 3 次 RPC 降为 1 次
- 用户感知延迟从 ~300-900ms 降为 ~100-300ms

---

## 优化 5：执行期间允许简短进度提示

### 问题

当前 prompt 完全禁止执行期间输出对话文字：

> 执行步骤期间，禁止输出介绍性、过渡性或总结性的对话文字

用户在长步骤（编译、部署、大文件操作）执行时只看到工具列表滚动，缺乏上下文感知，可能误以为系统卡住。

### 设计

修改 `plan-execution-context.md`，允许 **步骤开始时** 输出一行简短进度提示（限 50 字以内）：

```markdown
### 执行过程输出规范

- 步骤开始时，**可以**输出一行简短进度提示（不超过 50 字），如"正在安装依赖..."
- 步骤执行过程中和步骤之间，**禁止**输出解释性、过渡性或总结性文字
- 步骤成果只记录在 `resultSummary` 中
```

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/resources/prompts/plan-execution-context.md` | 放宽输出规范 |

### 预期效果

- 用户能看到当前步骤在做什么
- 不会产生冗余文字（限 50 字 + 仅步骤开始时）

---

## 优化 6：计划完成摘要持久化

### 问题

计划完成时，Agent 输出执行回顾到聊天消息中。但 `PlanEntity` 没有 `completionSummary` 字段，用户在 `PlanHistoryTab` 中查看历史计划时看不到整体回顾。

### 设计

1. `PlanEntity` 新增 `completionSummary` 字段（TEXT 类型）
2. `PlanService.completePlan` 将 `summary` 参数保存到该字段
3. 数据库迁移：`ALTER TABLE plan ADD COLUMN completion_summary TEXT`
4. `PlanController.getPlanById` 返回 `completionSummary`
5. `PlanHistoryTab` 显示完成摘要

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/PlanEntity.java` | 新增 `completionSummary` 字段 |
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java` | `completePlan` 保存摘要 |
| DB 迁移脚本 | ALTER TABLE |
| `intellimate-web/src/components/PlanHistoryTab.tsx` | 显示摘要 |

### 预期效果

- 历史计划可查看完成回顾
- 为后续的计划分析/复盘功能提供数据基础

---

## 优化 7：步骤重编号加事务保护

### 问题

`addStep` 和 `removeStep` 对步骤重新编号时使用 `flatMap`（并发保存），在有 `(plan_id, step_index)` 唯一约束的情况下可能产生临时冲突。并发修改（用户同时 add 和 reorder）时可能数据不一致。

### 设计

**方案 A（推荐）：改 flatMap 为 concatMap**

重编号逻辑改为串行保存，避免 stepIndex 冲突：

```java
Flux<PlanStepEntity> reindex = Flux.fromIterable(existingSteps)
        .filter(s -> s.getStepIndex() >= newIndex)
        .sort(Comparator.comparingInt(PlanStepEntity::getStepIndex).reversed())
        .concatMap(s -> {
            s.setStepIndex(s.getStepIndex() + 1);
            return planStepRepository.save(s);
        });
```

**方案 B：用临时 stepIndex 避免冲突**

先将所有受影响步骤的 stepIndex 设为负值（如 -stepIndex），然后重新分配正确值。

**决策：采用方案 A。** 步骤数量通常 < 15，串行保存的延迟可接受。且逆序保存（大 index 先加 1）避免唯一约束冲突。

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java` | `addStep` 和 `removeStep` 改为 concatMap + 逆序 |

### 预期效果

- 消除步骤编号冲突风险
- 并发操作下数据一致性保证

---

## 优化 8：Plan 历史从服务端加载

### 问题

`planStore` 的 `planHistory` 在内存中维护最近 5 个已完成计划。刷新页面后历史丢失。`PlanHistoryTab` 从服务端加载历史但位于管理页面，`PlanPanel` 右侧的翻页导航使用内存数据。

### 设计

在 `PlanPanel` 打开时（或 WebSocket 重连后），从服务端加载当前 session 的最近 5 个已完成计划。

1. `PlanController` 已有 `GET /api/plans` 接口
2. `planStore` 新增 `loadHistoryFromServer(sessionId)` 方法
3. `PlanPanel` 在 mount 时调用

```typescript
loadHistoryFromServer: async (sessionId: number) => {
    const plans = await fetchPlans({ sessionId, status: 'completed,cancelled', limit: 5 });
    set({ planHistory: plans });
},
```

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-web/src/stores/planStore.ts` | 新增 `loadHistoryFromServer` |
| `intellimate-web/src/components/PlanPanel.tsx` | mount 时加载历史 |
| `intellimate-web/src/lib/api.ts` | 确保 `fetchPlans` 支持筛选参数 |

### 预期效果

- 刷新页面后计划历史不丢失

---

## 优化 9：plan-system.md 排除任务管理工具

### 问题

当前 `plan-system.md` 的 "何时创建计划" 规则以 "3 个以上独立步骤" 为判断标准。引入任务管理工具后，用户说 "帮我设 3 个提醒" 可能触发模型创建一个 3 步计划，每步调 `createTodoTask`，而不是直接连续调 3 次工具。

### 设计

在 `plan-system.md` 的 "何时不创建计划" 部分追加：

```markdown
### 何时不创建计划
- 单一文件的修改或查询
- 信息查询类请求
- 简单的代码生成
- **批量创建任务/提醒/定时任务**（直接调用 createTodoTask 或 createScheduledJob，不需要计划）
- **使用其他管理类工具的批量操作**（如连续创建、更新、删除）
```

### 修改文件

| 文件 | 修改 |
|------|------|
| `intellimate-agent/src/main/resources/prompts/plan-system.md` | "何时不创建计划" 追加条目 |

### 预期效果

- 避免对简单批量操作创建冗余计划

---

## 实现优先级

| 阶段 | 优化项 | 理由 |
|------|--------|------|
| Phase 1（低风险，立即可做） | #1、#5、#9 | 仅修改 prompt 文件，无代码变更 |
| Phase 2（低难度代码变更） | #2、#3、#6 | 独立的后端/前端修改，不影响核心流程 |
| Phase 3（中等难度） | #4、#7、#8 | 涉及新 RPC 或状态管理变更 |

---

## 测试策略

### Phase 1（Prompt 变更）
- 端到端对话测试：创建 5-10 步计划并执行，验证步骤生命周期事件完整性
- 对比测试：记录优化前后的 LLM 回合数和 token 消耗

### Phase 2
- `PlanService.createPlan` 单元测试：验证并发保存 + 验证查询的正确性
- 前端：移除轮询后，模拟 WebSocket 断连场景验证恢复逻辑
- DB 迁移测试：验证 `completion_summary` 字段正确保存和读取

### Phase 3
- `plan.approve_and_execute` 集成测试：从审批到 Agent 开始执行的完整链路
- `addStep`/`removeStep` 并发测试：模拟同时 add + reorder
- 前端历史加载测试：刷新后验证历史计划可见
