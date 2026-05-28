# 工具调用展示优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将非 Plan 模式下的工具调用从多卡片独立展示改为单一工具条 + 模态框时间线，减少视觉噪音。

**架构：** 新增 `ToolCallBar` 组件替代 `MessageBubble` 中的 `toolCallGroups.map(...)` 循环，点击后弹出 `ToolCallTimelineModal` 展示纵向时间线。`chatStore` 的 `ToolCallInfo` 新增 `startTime` 和 `duration` 字段支持耗时计算。

**技术栈：** React 19 + TypeScript + Tailwind CSS 4 + Zustand

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-web/src/stores/chatStore.ts` | 修改 | `ToolCallInfo` 新增 `startTime`/`duration` 字段；`addToolCall` 记录 `startTime`；`updateToolResult` 计算 `duration` |
| `intellimate-web/src/components/ToolCallBar.tsx` | 新建 | 单一工具条标签，显示工具调用摘要，三种状态（执行中/完成/有失败） |
| `intellimate-web/src/components/ToolCallTimelineModal.tsx` | 新建 | 模态框 + 纵向时间线链路，展示每个工具调用的详情 |
| `intellimate-web/src/components/MessageBubble.tsx` | 修改 | 将 `toolCallGroups.map(...)` 替换为 `<ToolCallBar>` |

---

### 任务 1：chatStore 新增耗时字段

**文件：**
- 修改：`intellimate-web/src/stores/chatStore.ts:23-32`（ToolCallInfo 接口）
- 修改：`intellimate-web/src/stores/chatStore.ts:439-470`（addToolCall / updateToolResult 实现）

- [ ] **步骤 1：修改 ToolCallInfo 接口**

在 `ToolCallInfo` 接口中新增两个可选字段：

```typescript
export interface ToolCallInfo {
  toolCallId: string;
  name: string;
  description?: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status: "calling" | "done" | "error";
  turn?: number;
  startTime?: number;
  duration?: number;
}
```

- [ ] **步骤 2：修改 addToolCall 记录 startTime**

在 `addToolCall` 实现中，创建新的 `ToolCallInfo` 时添加 `startTime: Date.now()`：

```typescript
addToolCall: (requestId, info) => {
  set((state) => updateAgentMessages(state, (msgs) =>
    msgs.map((msg) =>
      msg.id === `assistant-${requestId}`
        ? {
            ...msg,
            toolCalls: [
              ...(msg.toolCalls ?? []),
              { ...info, status: "calling" as const, startTime: Date.now() },
            ],
          }
        : msg,
    ),
  ));
},
```

- [ ] **步骤 3：修改 updateToolResult 计算 duration**

在 `updateToolResult` 实现中，计算 `duration`：

```typescript
updateToolResult: (requestId, toolCallId, result, success) => {
  const now = Date.now();
  set((state) => updateAgentMessages(state, (msgs) =>
    msgs.map((msg) =>
      msg.id === `assistant-${requestId}`
        ? {
            ...msg,
            toolCalls: msg.toolCalls?.map((tc) =>
              tc.toolCallId === toolCallId
                ? {
                    ...tc,
                    result,
                    success,
                    status: (success ? "done" : "error") as ToolCallInfo["status"],
                    duration: tc.startTime ? now - tc.startTime : undefined,
                  }
                : tc,
            ),
          }
        : msg,
    ),
  ));
},
```

- [ ] **步骤 4：验证编译通过**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无编译错误

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/stores/chatStore.ts
git commit -m "feat: ToolCallInfo 新增 startTime/duration 字段支持耗时计算"
```

---

### 任务 2：创建 ToolCallTimelineModal 组件

**文件：**
- 创建：`intellimate-web/src/components/ToolCallTimelineModal.tsx`

- [ ] **步骤 1：创建 ToolCallTimelineModal 组件**

```tsx
import { useState, useEffect, useCallback } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import {
  X, CheckCircle2, XCircle, Loader2,
  ChevronRight, ChevronDown,
} from "lucide-react";

interface ToolCallTimelineModalProps {
  toolCalls: ToolCallInfo[];
  open: boolean;
  onClose: () => void;
}

function formatDuration(ms?: number): string {
  if (ms == null) return "";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function TimelineNode({ tc }: { tc: ToolCallInfo }) {
  const [expanded, setExpanded] = useState(false);
  const hasDetail = !!(tc.arguments || tc.result !== undefined);
  const MAX_CONTENT = 2000;

  const dotClass =
    tc.status === "calling"
      ? "bg-blue-500 animate-pulse"
      : tc.status === "done"
        ? "bg-emerald-500"
        : "bg-red-500";

  return (
    <div className="relative py-2">
      <div
        className={`absolute -left-[23px] top-[14px] h-3 w-3 rounded-full border-2 border-white shadow-[0_0_0_1px_#e2e8f0] dark:border-slate-900 dark:shadow-[0_0_0_1px_#334155] ${dotClass}`}
      />
      <button
        type="button"
        className="flex w-full items-center gap-2 text-left hover:bg-slate-50 dark:hover:bg-slate-800/30 -mx-2 px-2 rounded-lg transition-colors"
        onClick={() => hasDetail && setExpanded(!expanded)}
      >
        <span className="text-[13px] font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {tc.name}
        </span>
        <span
          className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
            tc.status === "done"
              ? "text-emerald-600 bg-emerald-50 dark:text-emerald-400 dark:bg-emerald-900/30"
              : tc.status === "error"
                ? "text-red-600 bg-red-50 dark:text-red-400 dark:bg-red-900/30"
                : "text-blue-600 bg-blue-50 dark:text-blue-400 dark:bg-blue-900/30"
          }`}
        >
          {tc.status === "calling" ? "调用中" : tc.status === "done" ? "完成" : "失败"}
        </span>
        {tc.duration != null && (
          <span className="text-[10px] text-slate-400 dark:text-slate-500">
            {formatDuration(tc.duration)}
          </span>
        )}
        {hasDetail && (
          expanded
            ? <ChevronDown size={10} className="text-slate-400 flex-shrink-0" />
            : <ChevronRight size={10} className="text-slate-400 flex-shrink-0" />
        )}
      </button>
      {tc.description && (
        <div className="text-[11px] text-slate-400 dark:text-slate-500 mt-0.5 truncate">
          {tc.description}
        </div>
      )}
      {expanded && (
        <div className="mt-2 space-y-1.5">
          {tc.arguments && (
            <pre className="text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-28 overflow-auto rounded-md bg-slate-50 dark:bg-slate-800/60 px-2.5 py-1.5 text-slate-500 dark:text-slate-400">
              {tc.arguments.length > MAX_CONTENT ? tc.arguments.slice(0, MAX_CONTENT) + "..." : tc.arguments}
            </pre>
          )}
          {tc.result !== undefined && (
            <pre
              className={`text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-32 overflow-auto rounded-md px-2.5 py-1.5 ${
                tc.success === false
                  ? "bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400"
                  : "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400"
              }`}
            >
              {tc.result.length > MAX_CONTENT ? tc.result.slice(0, MAX_CONTENT) + "..." : tc.result}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

export default function ToolCallTimelineModal({ toolCalls, open, onClose }: ToolCallTimelineModalProps) {
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    },
    [onClose],
  );

  useEffect(() => {
    if (open) {
      document.addEventListener("keydown", handleKeyDown);
      return () => document.removeEventListener("keydown", handleKeyDown);
    }
  }, [open, handleKeyDown]);

  if (!open) return null;

  const doneCount = toolCalls.filter((tc) => tc.status === "done").length;
  const errorCount = toolCalls.filter((tc) => tc.status === "error").length;
  const callingCount = toolCalls.filter((tc) => tc.status === "calling").length;
  const totalDuration = toolCalls.reduce((sum, tc) => sum + (tc.duration ?? 0), 0);

  let summaryParts: string[] = [];
  summaryParts.push(`共 ${toolCalls.length} 次调用`);
  if (doneCount > 0) summaryParts.push(`${doneCount} 成功`);
  if (errorCount > 0) summaryParts.push(`${errorCount} 失败`);
  if (callingCount > 0) summaryParts.push(`${callingCount} 进行中`);
  if (totalDuration > 0) summaryParts.push(formatDuration(totalDuration));

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="w-[480px] max-h-[80vh] bg-white dark:bg-slate-900 rounded-2xl shadow-2xl overflow-hidden animate-[modalIn_200ms_ease-out]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 dark:border-slate-800">
          <div>
            <h3 className="text-[15px] font-semibold text-slate-800 dark:text-slate-100">
              工具调用链路
            </h3>
            <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              {summaryParts.join(" · ")}
            </div>
          </div>
          <button
            type="button"
            className="w-7 h-7 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 flex items-center justify-center hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            onClick={onClose}
          >
            <X size={14} />
          </button>
        </div>
        <div className="px-5 py-4 max-h-[60vh] overflow-y-auto">
          <div className="ml-2 border-l-2 border-slate-200 dark:border-slate-700 pl-5">
            {toolCalls.map((tc) => (
              <TimelineNode key={tc.toolCallId} tc={tc} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：在 globals.css 中添加 modalIn 动画关键帧**

检查 `intellimate-web/src/styles/globals.css` 是否已有 `modalIn` 关键帧。如果没有，添加：

```css
@keyframes modalIn {
  from { opacity: 0; transform: scale(0.95) translateY(10px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}
```

- [ ] **步骤 3：验证编译通过**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无编译错误

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/components/ToolCallTimelineModal.tsx intellimate-web/src/styles/globals.css
git commit -m "feat: 创建工具调用时间线模态框组件"
```

---

### 任务 3：创建 ToolCallBar 组件

**文件：**
- 创建：`intellimate-web/src/components/ToolCallBar.tsx`

- [ ] **步骤 1：创建 ToolCallBar 组件**

```tsx
import { useState, useMemo } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import {
  CheckCircle2, XCircle, Loader2, AlertTriangle,
  ChevronRight,
} from "lucide-react";
import ToolCallTimelineModal from "./ToolCallTimelineModal";

interface ToolCallBarProps {
  toolCalls: ToolCallInfo[];
}

function formatTotalDuration(toolCalls: ToolCallInfo[]): string {
  const total = toolCalls.reduce((sum, tc) => sum + (tc.duration ?? 0), 0);
  if (total === 0) return "";
  if (total < 1000) return `${total}ms`;
  return `${(total / 1000).toFixed(1)}s`;
}

export default function ToolCallBar({ toolCalls }: ToolCallBarProps) {
  const [modalOpen, setModalOpen] = useState(false);

  const { doneCount, errorCount, callingCount, currentToolName } = useMemo(() => {
    let done = 0;
    let error = 0;
    let calling = 0;
    let currentName = "";
    for (const tc of toolCalls) {
      if (tc.status === "done") done++;
      else if (tc.status === "error") error++;
      else if (tc.status === "calling") {
        calling++;
        currentName = tc.name;
      }
    }
    return { doneCount: done, errorCount: error, callingCount: calling, currentToolName: currentName };
  }, [toolCalls]);

  const isRunning = callingCount > 0;
  const allDone = !isRunning;
  const hasError = errorCount > 0;
  const allFailed = errorCount === toolCalls.length;

  const borderColor = isRunning
    ? "border-l-blue-500"
    : allFailed
      ? "border-l-red-500"
      : hasError
        ? "border-l-amber-500"
        : "border-l-emerald-500";

  const durationText = allDone ? formatTotalDuration(toolCalls) : "";

  let label: string;
  let icon: React.ReactNode;

  if (isRunning) {
    label = `正在调用 ${currentToolName}...`;
    icon = <Loader2 size={14} className="animate-spin text-blue-500 flex-shrink-0" />;
  } else if (allFailed) {
    label = `${toolCalls.length} 个调用全部失败`;
    icon = <XCircle size={14} className="text-red-500 flex-shrink-0" />;
  } else if (hasError) {
    label = `${doneCount} 完成, ${errorCount} 失败`;
    icon = <AlertTriangle size={14} className="text-amber-500 flex-shrink-0" />;
  } else {
    label = `已完成 ${toolCalls.length} 个工具调用`;
    icon = <CheckCircle2 size={14} className="text-emerald-500 flex-shrink-0" />;
  }

  const completedCount = doneCount + errorCount;

  return (
    <>
      <button
        type="button"
        className={`my-1.5 flex w-full items-center gap-2 rounded-[10px] border border-slate-200 dark:border-slate-700/50 border-l-[3px] ${borderColor} bg-white dark:bg-slate-900 px-3.5 py-2.5 text-left shadow-sm hover:shadow transition-shadow cursor-pointer`}
        onClick={() => setModalOpen(true)}
      >
        {icon}
        <span className="text-[13px] font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {label}
        </span>
        {isRunning && completedCount > 0 && (
          <span className="text-[11px] text-slate-400 dark:text-slate-500">
            {completedCount} 已完成
          </span>
        )}
        {durationText && (
          <span className="text-[11px] text-slate-400 dark:text-slate-500">{durationText}</span>
        )}
        <ChevronRight size={12} className="text-slate-400 dark:text-slate-500 flex-shrink-0" />
      </button>
      <ToolCallTimelineModal
        toolCalls={toolCalls}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
```

- [ ] **步骤 2：验证编译通过**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无编译错误

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/components/ToolCallBar.tsx
git commit -m "feat: 创建工具条标签组件"
```

---

### 任务 4：修改 MessageBubble 接入 ToolCallBar

**文件：**
- 修改：`intellimate-web/src/components/MessageBubble.tsx:1-12`（imports）
- 修改：`intellimate-web/src/components/MessageBubble.tsx:99-112`（toolCallGroups memo 移除或替换）
- 修改：`intellimate-web/src/components/MessageBubble.tsx:151-161`（渲染区域）

- [ ] **步骤 1：添加 ToolCallBar 导入**

在 `MessageBubble.tsx` 的 import 区域，添加 `ToolCallBar` 导入，移除不再直接使用的 `ToolCallGroup` 导入：

将：
```typescript
import ToolCallGroup from "./ToolCallGroup";
```

替换为：
```typescript
import ToolCallBar from "./ToolCallBar";
```

- [ ] **步骤 2：简化 toolCalls 数据准备**

将原有的 `toolCallGroups` useMemo（按 turn 分组）替换为简单的过滤逻辑，因为 `ToolCallBar` 接收扁平的 `ToolCallInfo[]`：

将 `MessageBubble.tsx` 中的 `toolCallGroups` useMemo：
```typescript
const toolCallGroups = useMemo(() => {
  if (!hasToolCalls) return [];
  if (showStepView) return [];
  const calls = hasPlanTools
    ? message.toolCalls!.filter(tc => tc.name !== "writePlan" && tc.name !== "updatePlan")
    : message.toolCalls!;
  if (calls.length === 0) return [];
  return groupByTurn(calls);
}, [
  hasToolCalls,
  showStepView,
  hasPlanTools,
  message.toolCalls,
]);
```

替换为：
```typescript
const filteredToolCalls = useMemo(() => {
  if (!hasToolCalls) return [];
  if (showStepView) return [];
  const calls = hasPlanTools
    ? message.toolCalls!.filter(tc => tc.name !== "writePlan" && tc.name !== "updatePlan")
    : message.toolCalls!;
  return calls;
}, [
  hasToolCalls,
  showStepView,
  hasPlanTools,
  message.toolCalls,
]);
```

- [ ] **步骤 3：替换渲染区域**

将原有的 `toolCallGroups.map(...)` 渲染：
```tsx
{toolCallGroups.length > 0 && (
  <div className="mb-2">
    {toolCallGroups.map((group) => (
      <ToolCallGroup
        key={group.key}
        calls={group.calls}
        turn={group.turn}
      />
    ))}
  </div>
)}
```

替换为：
```tsx
{filteredToolCalls.length > 0 && (
  <div className="mb-2">
    <ToolCallBar toolCalls={filteredToolCalls} />
  </div>
)}
```

- [ ] **步骤 4：清理不再使用的代码**

移除 `MessageBubble.tsx` 底部不再使用的 `ToolCallGroupData` 接口和 `groupByTurn` 函数（第 215-248 行）。这些代码在新方案下不再被任何地方引用。

- [ ] **步骤 5：验证编译通过**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无编译错误

- [ ] **步骤 6：Commit**

```bash
git add intellimate-web/src/components/MessageBubble.tsx
git commit -m "feat: MessageBubble 接入 ToolCallBar 替代多卡片展示"
```

---

### 任务 5：暗色模式适配与视觉验证

**文件：**
- 修改：`intellimate-web/src/components/ToolCallBar.tsx`（如需调整）
- 修改：`intellimate-web/src/components/ToolCallTimelineModal.tsx`（如需调整）

- [ ] **步骤 1：验证开发服务器启动**

运行：`cd intellimate-web && npm run dev`
预期：开发服务器正常启动

- [ ] **步骤 2：视觉验证清单**

在浏览器中依次验证以下场景：

1. 发送一条会触发工具调用的消息
2. 确认工具条标签在执行中显示"正在调用 {工具名}..."和旋转动画
3. 确认完成后显示"已完成 N 个工具调用"
4. 点击工具条，确认模态框弹出
5. 确认时间线节点正确显示工具名、状态标签、耗时
6. 点击时间线节点，确认参数/结果展开
7. 按 ESC 或点击遮罩，确认模态框关闭
8. 切换到暗色模式，确认所有元素视觉正确
9. 确认 Plan 模式下仍然显示分步骤视图（不显示工具条）

- [ ] **步骤 3：修复发现的问题**

根据视觉验证结果，修复发现的样式或交互问题。

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "fix: 工具调用展示暗色模式适配与视觉调整"
```

---

### 任务 6：回归验证

- [ ] **步骤 1：验证 Plan 模式不受影响**

触发一个使用 Plan 的任务，确认：
- 工具调用仍然按步骤分组显示（`StepGroupedTools`）
- 不出现 `ToolCallBar`

- [ ] **步骤 2：验证 Workflow Timeline 不受影响**

如果有 delegation/parallel 工作流场景，确认 `WorkflowTimeline` 正常渲染。

- [ ] **步骤 3：验证 ActivityStrip 共存**

确认在工具调用过程中：
- `ActivityStrip`（顶部实时状态条）正常显示
- `ToolCallBar`（工具调用记录）正常显示
- 两者互不干扰

- [ ] **步骤 4：验证编译和类型检查**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无编译错误

- [ ] **步骤 5：最终 Commit**

```bash
git add -A
git commit -m "chore: 工具调用展示优化回归验证通过"
```
