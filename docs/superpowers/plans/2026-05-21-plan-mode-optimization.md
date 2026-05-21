# Plan Mode 优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 优化 Plan Mode 的执行效率、用户体验和数据健壮性，减少 LLM 回合消耗、消除不必要轮询、简化审批流程。

**架构：** Phase 1 仅修改 Prompt 文件（零代码风险）。Phase 2 修改 `PlanService` 保存策略 + 前端轮询逻辑 + DB schema。Phase 3 新增后端 RPC + 前端协议 + 步骤重编号保护 + 历史持久化。

**技术栈：** Java 21 / Spring WebFlux / R2DBC / React 19 / TypeScript / Zustand

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-gateway/.../prompts/plan-execution-context.md` | 修改 | 放宽 markStep 并行限制 + 允许简短进度提示 |
| `intellimate-agent/.../prompts/plan-system.md` | 修改 | 排除任务管理工具的计划触发 |
| `intellimate-gateway/.../service/PlanService.java` | 修改 | 批量保存 + 步骤重编号保护 + completionSummary 保存 |
| `intellimate-gateway/.../entity/PlanEntity.java` | 修改 | 新增 completionSummary 字段 |
| `intellimate-gateway/.../pipeline/MessagePipeline.java` | 修改 | 新增 plan.approve_and_execute case |
| `intellimate-web/src/hooks/useWebSocket.ts` | 修改 | 移除固定轮询 + 超时兜底 |
| `intellimate-web/src/stores/planStore.ts` | 修改 | lastEventTimestamp + loadHistoryFromServer |
| `intellimate-web/src/lib/protocol.ts` | 修改 | 新增 createPlanApproveAndExecute |
| `intellimate-web/src/components/PlanPanel.tsx` | 修改 | 简化 handleApproveAndExecute + mount 加载历史 |
| `intellimate-web/src/components/PlanHistoryTab.tsx` | 修改 | 显示 completionSummary |
| `intellimate-gateway/.../db/migration/V28__plan_completion_summary.sql` | 新建 | ALTER TABLE plan ADD COLUMN completion_summary |
| `intellimate-gateway/.../controller/PlanController.java` | 修改 | 新增 sessionId + includeSteps 查询参数 |

---

### 任务 1：Phase 1 — 所有 Prompt 变更（合并提交）

> **[评审修复 CEO-3]** Phase 1 的 3 项 prompt 修改合并为一个 commit，简化回退和 cherry-pick。

**文件：**
- 修改：`intellimate-gateway/src/main/resources/prompts/plan-execution-context.md`
- 修改：`intellimate-agent/src/main/resources/prompts/plan-system.md`

- [ ] **步骤 1：放宽 markStep 独占回合限制**

将 `plan-execution-context.md` 中的步骤执行流程从：

```markdown
1. **开始**：在执行该步骤的任何工具之前，**必须先**单独调用：
   `updatePlan(planId=<上方 Plan ID>, action="markStep", stepIndex=N, status="in_progress")`
   不要与其他工具在同一轮中并行调用。
```

改为：

```markdown
1. **开始**：在执行该步骤时，调用：
   `updatePlan(planId=<上方 Plan ID>, action="markStep", stepIndex=N, status="in_progress")`
   可以与该步骤的第一个工具在同一轮并行调用。
```

- [ ] **步骤 2：更新禁止事项**

将：

```markdown
**禁止事项：**
- 禁止在未调用 `markStep(status="in_progress")` 之前执行该步骤的任何其他工具
- 禁止将 `markStep(status="in_progress")` 与其他工具在同一轮并行调用
```

改为：

```markdown
**禁止事项：**
- 禁止在未调用 `markStep(status="in_progress")` 之前执行该步骤的任何其他工具
- `markStep(status="in_progress")` 可以与该步骤的工具在同一轮并行调用
```

- [ ] **步骤 3：允许简短进度提示**

将 `plan-execution-context.md` 中的执行过程输出规范从：

```markdown
### 执行过程输出规范

- 执行步骤期间，**禁止**输出介绍性、过渡性或总结性的对话文字（如"好的，我将开始执行..."等）
- 直接调用工具，不要在工具调用之间插入解释文字
- 步骤成果只记录在 `resultSummary` 参数中，不要在对话正文中重复
```

改为：

```markdown
### 执行过程输出规范

- 步骤开始时，**可以**输出一行简短进度提示（不超过 50 字），如"正在安装依赖..."
- 步骤执行过程中和步骤之间，**禁止**输出解释性、过渡性或总结性文字
- 步骤成果只记录在 `resultSummary` 参数中，不要在对话正文中重复
```

- [ ] **步骤 4：plan-system.md 排除任务管理工具**

在 `plan-system.md` 的 `### 何时不创建计划` 列表末尾追加：

```markdown
- 批量创建任务/提醒/定时任务（直接调用 createTodoTask 或 createScheduledJob，不需要计划）
- 使用其他管理类工具的批量操作（如连续创建、更新、删除）
```

- [ ] **步骤 5：Commit（Phase 1 一次提交）**

```bash
git add intellimate-gateway/src/main/resources/prompts/plan-execution-context.md
git add intellimate-agent/src/main/resources/prompts/plan-system.md
git commit -m "perf(plan): Phase 1 prompt optimizations - parallel markStep, progress hints, exclude task tools"
```

---

### 任务 2：步骤批量保存优化

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java`

- [ ] **步骤 1：确认 plan_step 表的约束结构**

> **[评审修复 ENG-2]** 先确认 `(plan_id, step_index)` 是否有 UNIQUE 约束，再决定并发策略。

查看 `V12__plan_tables.sql`：`plan_step` 表对 `(plan_id, step_index)` 只有普通 INDEX（`idx_plan_step_plan` 仅在 `plan_id` 上），**没有 UNIQUE 约束**。因此 `flatMap` 并发保存不会产生唯一约束冲突。

- [ ] **步骤 2：查看现有 createPlan 实现**

阅读 `PlanService.createPlan` 方法，确认当前使用 `concatMap` 串行保存步骤的位置（约第 50-66 行）。

- [ ] **步骤 3：修改为并发保存**

将 `createPlan` 方法中的步骤保存逻辑从：

```java
return Flux.fromIterable(steps)
        .map(s -> {
            PlanStepEntity step = new PlanStepEntity();
            step.setPlanId(planId);
            step.setStepIndex(idx.getAndIncrement());
            step.setTitle(s.title());
            step.setDescription(s.description());
            step.setStatus("pending");
            return step;
        })
        .concatMap(step -> planStepRepository.save(step)
                .doOnNext(saved -> log.debug(
                        "createPlan: step saved id={}, planId={}, stepIndex={}",
                        saved.getId(), saved.getPlanId(), saved.getStepIndex())))
        .count()
        .flatMap(savedCount -> {
```

改为：

```java
List<PlanStepEntity> stepEntities = new ArrayList<>();
for (StepInput s : steps) {
    PlanStepEntity step = new PlanStepEntity();
    step.setPlanId(planId);
    step.setStepIndex(idx.getAndIncrement());
    step.setTitle(s.title());
    step.setDescription(s.description());
    step.setStatus("pending");
    stepEntities.add(step);
}
return Flux.fromIterable(stepEntities)
        .flatMap(step -> planStepRepository.save(step), 4)
        .collectList()
        .flatMap(savedSteps -> {
            long savedCount = savedSteps.size();
```

`flatMap` 的第二个参数 `4` 是最大并发度。`plan_step` 无 `(plan_id, step_index)` UNIQUE 约束，并发安全。

后续的验证查询保持不变。

- [ ] **步骤 4：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 5：运行已有测试**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest="*Plan*" -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java
git commit -m "perf(plan): parallelize step creation with bounded concurrency"
```

---

### 任务 3：去掉执行期 10 秒轮询

**文件：**
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`
- 修改：`intellimate-web/src/stores/planStore.ts`

- [ ] **步骤 1：在 planStore 中添加 lastPlanEventTimestamp**

在 `PlanState` interface 中添加：

```typescript
lastPlanEventTimestamp: number;
```

在 store 初始值中添加：

```typescript
lastPlanEventTimestamp: 0,
```

在每个 `handlePlan*` 方法中（`handlePlanCreated`、`handleStepStart`、`handleStepDone`、`handlePlanAdjusted`、`handlePlanStatusChanged`、`handlePlanCompleted`）的 `set(...)` 调用中追加：

```typescript
lastPlanEventTimestamp: Date.now(),
```

- [ ] **步骤 2：在 useWebSocket 中替换固定轮询**

找到 `useWebSocket.ts` 中设置 10 秒 `setInterval` 进行 `syncFromServer` 的位置。将固定 10 秒轮询替换为超时兜底逻辑：

```typescript
useEffect(() => {
    const planState = usePlanStore.getState();
    if (!planState.plan || !["executing", "approved"].includes(planState.plan.status)) return;

    const CHECK_INTERVAL = 15_000;
    const STALE_THRESHOLD = 60_000;

    const timer = setInterval(() => {
        const { plan, lastPlanEventTimestamp, syncFromServer } = usePlanStore.getState();
        if (!plan || !["executing", "approved"].includes(plan.status)) return;
        const elapsed = Date.now() - lastPlanEventTimestamp;
        if (elapsed > STALE_THRESHOLD) {
            syncFromServer(plan.planId);
        }
    }, CHECK_INTERVAL);

    return () => clearInterval(timer);
}, [/* plan status dependency */]);
```

具体实现需根据现有轮询代码的位置和依赖项调整。

- [ ] **步骤 3：保留重连后同步 + agent.done 兜底**

> **[评审修复 DES-4]** 确保 WebSocket 重连和 `agent.done` 双兜底。

确保以下两个恢复机制保持不变：
1. WebSocket 重连后（`session.welcome` 事件）的 `syncFromServer` 调用
2. `agent.done` 事件处理（`useWebSocket.ts` 84-104 行）中的步骤状态兜底

在 `agent.done` 处理逻辑末尾追加一次 `syncFromServer`，确保前端本地推断状态与服务端保持一致：

```typescript
// agent.done handler 末尾追加
const { plan, syncFromServer } = usePlanStore.getState();
if (plan && ["executing", "approved"].includes(plan.status)) {
    syncFromServer(plan.planId);
}
```

- [ ] **步骤 4：前端编译验证**

运行：`cd intellimate-web && npm run build`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/hooks/useWebSocket.ts
git add intellimate-web/src/stores/planStore.ts
git commit -m "perf(plan): replace fixed 10s polling with stale-event fallback"
```

---

### 任务 4：计划完成摘要持久化

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/PlanEntity.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java`
- 修改：`intellimate-web/src/components/PlanHistoryTab.tsx`

- [ ] **步骤 1：PlanEntity 新增字段**

在 `PlanEntity.java` 中添加：

```java
@Column("completion_summary")
private String completionSummary;

public String getCompletionSummary() { return completionSummary; }
public void setCompletionSummary(String completionSummary) { this.completionSummary = completionSummary; }
```

- [ ] **步骤 2：PlanService.completePlan 保存摘要**

> **[评审修复 CEO-1 + ENG-1]** 当前 `completePlan` 的 `summary` 参数被完全丢弃。重写此步骤以匹配实际链式结构。

`PlanService.completePlan` 的实际结构（第 177-192 行）是：

```java
public Mono<PlanEntity> completePlan(Long planId, String summary) {
    return planStepRepository.findByPlanIdOrderByStepIndex(planId)
            .collectList()
            .flatMap(steps -> {
                Flux<PlanStepEntity> skipPending = ...;
                return skipPending.then(Mono.empty());
            })
            .then(updatePlanStatus(planId, "completed"));
}
```

其中 `updatePlanStatus` 是私有方法（第 327-335 行）：`findById` → `setStatus` + `setUpdatedAt` → `save`。

将 `completePlan` 末尾的 `.then(updatePlanStatus(planId, "completed"))` 替换为内联逻辑以同时保存 `completionSummary`：

```java
            .then(planRepository.findById(planId)
                    .flatMap(plan -> {
                        log.info("Plan {} status: {} -> completed", planId, plan.getStatus());
                        plan.setStatus("completed");
                        plan.setCompletionSummary(summary);
                        plan.setUpdatedAt(LocalDateTime.now());
                        return planRepository.save(plan);
                    }));
```

不要修改 `updatePlanStatus` 私有方法本身——其他调用者（`approvePlan`、`pausePlan`、`resumePlan`、`cancelPlan`）不需要 `completionSummary`。

- [ ] **步骤 3：数据库迁移（Flyway V28）**

项目使用 Flyway（当前最新迁移为 `V27__remove_health_check_job.sql`）。创建迁移文件：

```bash
cat > intellimate-gateway/src/main/resources/db/migration/V28__plan_completion_summary.sql << 'EOF'
ALTER TABLE `plan` ADD COLUMN `completion_summary` TEXT NULL AFTER `status`;
EOF
```

- [ ] **步骤 4：PlanHistoryTab 显示摘要**

在 `PlanHistoryTab.tsx` 中，找到渲染单个计划条目的位置，在状态信息下方添加摘要显示：

```tsx
{plan.completionSummary && (
    <div className="text-xs text-gray-500 dark:text-gray-400 mt-1 line-clamp-3">
        {plan.completionSummary}
    </div>
)}
```

同时确保 `fetchPlans` API 返回的数据中包含 `completionSummary` 字段。检查 `PlanController` 是否直接返回 `PlanEntity`（如果是，字段会自动包含）。

- [ ] **步骤 5：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl . && cd ../intellimate-web && npm run build`
预期：全部 BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/PlanEntity.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java
git add intellimate-web/src/components/PlanHistoryTab.tsx
git commit -m "feat(plan): persist and display completion summary"
```

---

### 任务 5：审批-执行合并为一站式 RPC

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`
- 修改：`intellimate-web/src/lib/protocol.ts`
- 修改：`intellimate-web/src/components/PlanPanel.tsx`

- [ ] **步骤 1：查看 processPlanRequest 现有结构**

阅读 `MessagePipeline.processPlanRequest` 方法，了解 `plan.approve` 和 `plan.resume` 的现有处理逻辑。确认 `processMessageStreaming` 方法签名。

- [ ] **步骤 2：在 processPlanRequest 中新增 case**

> **[评审修复 ENG-3]** 不从前端传 `sessionId`，而是从 `planId` 反查，与现有 `plan.approve` 模式一致。
> **[评审修复 DES-2]** 失败时返回 `currentStatus` 字段，便于前端显示具体恢复操作。

在 `processPlanRequest` 方法的 `switch` 块中（在 `default` 之前），新增 `plan.approve_and_execute` case：

```java
case "plan.approve_and_execute" -> {
    Long planId = ((Number) params.get("planId")).longValue();

    // 从 planId 反查 sessionId（与 plan.approve 模式一致）
    yield planService.approvePlan(planId, true, null)
            .flatMap(approvedPlan -> planService.resumePlan(planId)
                    .thenReturn(approvedPlan))
            .flatMapMany(plan -> {
                EventFrame statusFrame = new EventFrame("plan.status_changed",
                        Map.of("planId", planId, "status", "executing"),
                        seqGenerator.incrementAndGet());
                ResponseFrame okResponse = new ResponseFrame(request.id(), true,
                        Map.of("planId", planId), null);

                return sessionRepository.findById(plan.getSessionId())
                        .flatMapMany(session ->
                                Flux.just((GatewayFrame) okResponse, (GatewayFrame) statusFrame)
                                        .concatWith(processMessageStreaming(
                                                session, "开始执行计划", request.id() + "-exec",
                                                wsSessionId, false)));
            })
            .onErrorResume(e -> {
                log.error("plan.approve_and_execute failed for planId={}: {}", planId, e.getMessage());
                // 返回当前状态，便于前端判断恢复操作
                return planRepository.findById(planId)
                        .map(p -> (GatewayFrame) new ResponseFrame(request.id(), false, 
                                Map.of("currentStatus", p.getStatus()), e.getMessage()))
                        .defaultIfEmpty(new ResponseFrame(request.id(), false, null, e.getMessage()));
            });
}
```

关键设计决策：
- `sessionId` 通过 `approvedPlan.getSessionId()` 获取（`approvePlan` 返回 `PlanEntity`）
- 失败时查询当前计划状态，返回 `currentStatus` 字段（如 `"approved"` 表示审批成功但执行未启动）
- 前端可根据 `currentStatus` 显示不同恢复提示

- [ ] **步骤 3：前端 protocol.ts 新增工厂函数**

在 `protocol.ts` 中添加：

```typescript
export function createPlanApproveAndExecute(planId: number): RequestFrame {
    return {
        type: "request",
        id: `plan-ae-${Date.now()}`,
        method: "plan.approve_and_execute",
        params: { planId },
    };
}
```

- [ ] **步骤 4：简化 PlanPanel.tsx handleApproveAndExecute**

将 `handleApproveAndExecute` 从 3 步串行调用（`approve` → `resume` → `sendMessage`）简化为 1 步。

> **[评审修复 DES-2]** 失败时根据 `currentStatus` 显示具体恢复提示。

```tsx
const handleApproveAndExecute = useCallback(async () => {
    if (!plan) return;
    setApproveStarting(true);
    try {
        const res = await onSendPlanActionAndWait(
            createPlanApproveAndExecute(plan.planId),
        );
        if (!res.ok) {
            const currentStatus = res.data?.currentStatus;
            if (currentStatus === "approved") {
                console.warn("[PlanPanel] approved but execution failed, user can retry via resume");
            }
            console.error("[PlanPanel] plan.approve_and_execute failed:", res.error);
        }
    } catch (e) {
        console.error("[PlanPanel] approve and execute:", e);
    } finally {
        setApproveStarting(false);
    }
}, [plan, onSendPlanActionAndWait]);
```

移除对 `onSendMessage` 的调用（后端会自动触发执行）。

- [ ] **步骤 5：更新 PlanPanel 的 import**

确保 `import { createPlanApproveAndExecute } from "../lib/protocol";` 被添加。同时检查 `onSendMessage` 是否还被其他地方使用。如果 `handleApproveAndExecute` 是唯一的调用者，可以保留 prop 但不使用（其他状态如 paused 的恢复执行仍需要 `onSendMessage`）。

- [ ] **步骤 6：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl . && cd ../intellimate-web && npm run build`
预期：全部 BUILD SUCCESS

- [ ] **步骤 7：集成测试（新 RPC 端点）**

> **[评审修复 ENG-5]** 新 RPC 必须有测试覆盖。

编写 `plan.approve_and_execute` 的集成测试（或在现有 `*Plan*Test` 中添加 case）：

1. **正常流程**：创建 draft 计划 → 发送 `approve_and_execute` → 验证计划状态变为 `executing`
2. **部分失败**：mock `resumePlan` 抛异常 → 验证返回 `currentStatus: "approved"` + 错误信息
3. **非法状态**：对 `executing` 状态的计划发送 `approve_and_execute` → 验证错误处理

运行：`cd intellimate-gateway && mvn test -pl . -Dtest="*Plan*" -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 8：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git add intellimate-web/src/lib/protocol.ts
git add intellimate-web/src/components/PlanPanel.tsx
git commit -m "feat(plan): add plan.approve_and_execute one-shot RPC"
```

---

### 任务 6：步骤重编号加事务保护

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java`

- [ ] **步骤 1：修改 addStep 的重编号逻辑**

在 `PlanService.addStep` 方法中，将步骤重编号从 `flatMap`（并发）改为 `concatMap`（串行）+ 逆序保存：

将：

```java
Flux<PlanStepEntity> reindex = Flux.fromIterable(existingSteps)
        .filter(s -> s.getStepIndex() >= newIndex)
        .flatMap(s -> {
            s.setStepIndex(s.getStepIndex() + 1);
            return planStepRepository.save(s);
        });
```

改为：

```java
List<PlanStepEntity> toReindex = existingSteps.stream()
        .filter(s -> s.getStepIndex() >= newIndex)
        .sorted(Comparator.comparingInt(PlanStepEntity::getStepIndex).reversed())
        .toList();
Flux<PlanStepEntity> reindex = Flux.fromIterable(toReindex)
        .concatMap(s -> {
            s.setStepIndex(s.getStepIndex() + 1);
            return planStepRepository.save(s);
        });
```

逆序（从大 index 到小 index）保存确保每次 +1 不会与尚未更新的步骤冲突。

- [ ] **步骤 2：修改 removeStep 的重编号逻辑**

在 `PlanService.removeStep` 方法中，将步骤重编号从 `flatMap` 改为 `concatMap`：

将：

```java
.flatMapMany(steps -> {
    AtomicInteger idx = new AtomicInteger(1);
    return Flux.fromIterable(steps)
            .flatMap(s -> {
                s.setStepIndex(idx.getAndIncrement());
                return planStepRepository.save(s);
            });
})
```

改为：

```java
.flatMapMany(steps -> {
    AtomicInteger idx = new AtomicInteger(1);
    return Flux.fromIterable(steps)
            .concatMap(s -> {
                s.setStepIndex(idx.getAndIncrement());
                return planStepRepository.save(s);
            });
})
```

`removeStep` 删除后步骤 index 从 1 重新分配，顺序保存即可。

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 4：运行已有测试**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest="*Plan*" -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/PlanService.java
git commit -m "fix(plan): serialize step reindex to prevent constraint violations"
```

> **[评审备注 ENG-4]** 并发竞态限制：`addStep`/`removeStep` 在 `collectList()` 和实际保存之间存在 TOCTOU 竞态窗口。当前步骤编辑频率极低（用户手动触发），实际竞态概率可忽略。如果未来需要支持高并发步骤编辑，需在方法入口加 `planId` 级别的锁（如 `ConcurrentHashMap<Long, Semaphore>`）。

---

### 任务 7：Plan 历史从服务端加载

**文件：**
- 修改：`intellimate-web/src/stores/planStore.ts`
- 修改：`intellimate-web/src/components/PlanPanel.tsx`
- 修改：`intellimate-web/src/lib/api.ts`

- [ ] **步骤 1：确认 fetchPlans API 及数据模型**

> **[评审修复 DES-1]** `PlanHistoryTab` 使用 `PlanSummary` 类型，`planStore.planHistory` 使用 `Plan` 类型（需要完整 `steps[]`）。必须解决数据模型不对齐问题。

查看 `PlanController` 的 `GET /api/plans` 接口，确认：
1. 是否支持 `sessionId` 查询参数（从 `PlanHistoryTab` 的 `fetchPlans` 看，支持 `agentName` 和 `status`，**不确定是否支持 `sessionId`**）
2. 是否返回步骤数据

方案：为 `GET /api/plans` 添加 `sessionId` 和 `includeSteps` 查询参数。当 `includeSteps=true` 时，每个 plan 对象嵌入完整的 `steps[]` 数组。

如果 `PlanController` 现有逻辑不支持这两个参数，需要在后端先添加：

```java
@GetMapping("/api/plans")
public Flux<PlanSummary> getPlans(
        @RequestParam(required = false) String agentName,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long sessionId,      // 新增
        @RequestParam(required = false, defaultValue = "false") boolean includeSteps,  // 新增
        @RequestParam(required = false, defaultValue = "20") int limit) {
    // ...
}
```

- [ ] **步骤 2：planStore 新增 loadHistoryFromServer**

在 `PlanState` interface 中添加：

```typescript
loadHistoryFromServer: (sessionId: number) => Promise<void>;
```

在 store 中实现（使用 `includeSteps=true` 获取完整数据）：

```typescript
async loadHistoryFromServer(sessionId: number) {
    try {
        const params = new URLSearchParams({
            sessionId: String(sessionId),
            status: "completed,cancelled,failed",
            includeSteps: "true",
            limit: "5",
        });
        const response = await fetch(`/api/plans?${params}`);
        if (!response.ok) return;
        const plans = await response.json();
        const historyPlans: Plan[] = plans.map((p: any) => ({
            planId: p.planId ?? p.id,
            title: p.title,
            status: p.status as PlanStatus,
            completionSummary: p.completionSummary,
            steps: (p.steps ?? []).map((s: any) => ({
                index: s.stepIndex,
                title: s.title,
                description: s.description || "",
                status: (s.status || "pending") as PlanStepStatus,
                resultSummary: s.resultSummary,
            })),
        }));
        set({ planHistory: historyPlans });
    } catch (e) {
        console.error("[planStore] loadHistoryFromServer failed:", e);
    }
},
```

- [ ] **步骤 3：PlanPanel mount 时加载历史**

在 `PlanPanel.tsx` 中，添加 `useEffect` 在组件 mount 时加载：

```tsx
const loadHistoryFromServer = usePlanStore((s) => s.loadHistoryFromServer);

useEffect(() => {
    // 当前 session ID 需要从 chatStore 或 props 获取
    const sessionId = /* 从 chatStore 获取当前 sessionId */;
    if (sessionId) {
        loadHistoryFromServer(sessionId);
    }
}, [loadHistoryFromServer]);
```

具体的 `sessionId` 获取方式需查看现有代码中 session 信息的存储位置。

- [ ] **步骤 4：前端编译验证**

运行：`cd intellimate-web && npm run build`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/stores/planStore.ts
git add intellimate-web/src/components/PlanPanel.tsx
git add intellimate-web/src/lib/api.ts
git commit -m "feat(plan): load plan history from server on panel mount"
```

---

### 任务 8：端到端验证

- [ ] **步骤 1：后端编译和启动**

运行：`cd intellimate-gateway && mvn install -DskipTests && mvn spring-boot:run`
预期：正常启动

- [ ] **步骤 2：前端编译和启动**

运行：`cd intellimate-web && npm run dev`
预期：Vite dev server 启动

- [ ] **步骤 3：采集 before 基线指标**

> **[评审修复 CEO-4]** 具体的 before/after 指标采集，而非"观察速度"。

在优化代码部署前（或使用未修改分支），记录以下指标：

| 指标 | 测量方法 | 目标 |
|------|----------|------|
| 步骤执行回合数 | 创建 5 步计划并执行，统计从 `writePlan` 到 `completePlan` 的 LLM 回合总数 | 从 ~12 降为 ~7 |
| 计划创建延迟 | 在 `PlanService.createPlan` 入口/出口加日志时间戳（或通过 WebSocket 响应时间） | 从 ~N×RTT 降为 ~2×RTT |
| 轮询请求数 | DevTools Network 过滤 `/api/plans`，记录 60 秒内的请求次数 | 从 ~6 次降为 ~0-1 次 |
| 审批-执行延迟 | DevTools Network 记录从点击"批准并执行"到收到 `plan.status_changed` 事件的总时间 | 从 ~300-900ms 降为 ~100-300ms |

记录到临时文件 `docs/superpowers/plans/plan-optimization-metrics.md`。

- [ ] **步骤 4：验证 Phase 1（Prompt 变更）**

在聊天中发送需要创建计划的请求（如 "帮我搭建一个 Node.js 项目，包含 Express 服务器、数据库连接和用户认证"）。

验证：
1. Agent 创建计划（`writePlan`）
2. 批准并执行后，Agent 在每个步骤中 `markStep(in_progress)` 与工具调用在同一回合
3. 步骤开始时有简短进度提示（不超过 50 字）

- [ ] **步骤 5：验证 Phase 2（批量保存 + 轮询）**

1. 对比创建计划的日志时间戳（应比 before 基线更快）
2. 打开浏览器 DevTools Network 面板，验证执行期间不再每 10 秒请求 `/api/plans`
3. 验证 `PlanHistoryTab` 中已完成的计划显示 `completionSummary`

- [ ] **步骤 6：验证 Phase 3（一站式审批 + 历史加载）**

1. 创建新计划后点击"批准并执行"
2. DevTools Network 验证只发送一个 WebSocket 请求（不再是 3 个）
3. 刷新页面后打开 PlanPanel，验证历史计划仍然可见（从服务端加载）
4. 验证审批失败时前端显示具体状态信息（如"计划已批准但执行未启动"）

- [ ] **步骤 7：验证任务工具不触发计划**

发送："帮我设 3 个提醒：明天 9 点开会、下午 3 点打电话、晚上 8 点健身"
预期：Agent 直接连续调用 3 次 `createTodoTask`，不创建计划

- [ ] **步骤 8：采集 after 指标并对比**

重复步骤 3 的测量，将 before/after 数据填入 `plan-optimization-metrics.md`，验证是否达到预期目标。

- [ ] **步骤 9：Commit 最终修复（如有）**

```bash
git add -A && git commit -m "fix: adjustments from plan-mode optimization e2e verification"
```

---

## 实现顺序依赖图

```
任务 1 (Phase 1: 所有 Prompt 变更) ──── 独立 ──┐
                                                │
任务 2 (批量保存) ─── 独立 ────────────────────┤
任务 3 (去轮询) ─── 独立 ─────────────────────┤──→ Phase 2
任务 4 (completionSummary) ── 独立 ────────────┤
                                                │
任务 5 (审批一站式) ─── 独立 ──────────────────┤
任务 6 (重编号保护) ─── 独立 ──────────────────┤──→ Phase 3
任务 7 (历史服务端加载) ── 依赖任务 4（schema）┤
                                                │
全部完成 ─────────────────────────── 任务 8 (E2E)
```

**并行组：**
- 任务 1 独立（Phase 1，纯 prompt）
- 任务 2-4 互相独立，可并行（Phase 2）
- 任务 5-6 互相独立，可并行（Phase 3）
- 任务 7 依赖任务 4（需要 `completion_summary` 字段和 `includeSteps` API 变更）
- 任务 8 依赖全部

**评审修复追踪：**

| 评审编号 | 修复位置 | 状态 |
|----------|----------|------|
| CEO-1 | 任务 4 步骤 2 | 已修复：`completePlan` 内联替换 `updatePlanStatus` |
| CEO-3 | 任务 1 | 已修复：3 个 prompt 改动合并为 1 个 commit |
| CEO-4 | 任务 8 步骤 3/8 | 已修复：加入 before/after 指标采集 |
| DES-1 | 任务 7 步骤 1 | 已修复：API 加 `includeSteps=true` + 后端返回完整 steps |
| DES-2 | 任务 5 步骤 2/4 | 已修复：错误返回 `currentStatus` 字段 |
| DES-4 | 任务 3 步骤 3 | 已修复：`agent.done` 追加 `syncFromServer` 兜底 |
| ENG-1 | 任务 4 步骤 2 | 已修复：根据实际链式结构重写 |
| ENG-2 | 任务 2 步骤 1 | 已修复：确认无 UNIQUE 约束 |
| ENG-3 | 任务 5 步骤 2 | 已修复：从 `planId` 反查 `sessionId` |
| ENG-4 | 任务 6 步骤 5 | 已修复：备注并发竞态限制 |
| ENG-5 | 任务 5 步骤 7 | 已修复：新增集成测试步骤 |
