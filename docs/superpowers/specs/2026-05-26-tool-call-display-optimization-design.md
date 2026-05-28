# 工具调用展示优化设计

## 背景与问题

当 Agent 在执行任务时使用大量工具（如 MCP 工具），当前的会话窗口会为每个工具调用渲染一个独立的 `ToolCallCard`，导致：

- 12+ 个工具调用卡片占满整个聊天窗口
- 用户难以追踪对话内容，实际回复文本被淹没
- 不同 turn 的顺序调用无法被现有 `groupByTurn` 逻辑折叠

现有分组逻辑仅在同一 `turn`（LLM 推理轮次）内的多个并行调用才折叠，跨 turn 的顺序调用各自独立显示。

## 设计目标

1. 将所有工具调用合并为**一个工具条标签**，极大减少视觉噪音
2. 提供**模态框时间线**查看完整执行链路
3. 保持与现有 Plan 模式的兼容性（Plan 模式分步骤展示不变）

## 作用范围

- **生效范围**：非 Plan 模式下的工具调用展示
- **不受影响**：Plan 模式的 `StepGroupedTools` 展示保持不变
- **不受影响**：`WorkflowTimeline` 展示保持不变

## 设计方案

### 1. 工具条标签（ToolCallBar）

替代原有的多个 `ToolCallGroup` / `ToolCallCard`，在消息气泡上方只渲染一个工具条。

**三种状态：**

| 状态 | 视觉表现 | 文字内容 |
|------|---------|---------|
| 执行中 | 蓝色左边框 + 旋转动画 | "正在调用 {当前工具名}..." + 右侧显示已完成计数（如 "3 已完成"） |
| 全部完成 | 绿色左边框 + 勾选图标 | "已完成 N 个工具调用" + 总耗时 |
| 有失败 | 橙色左边框 + 警告图标 | "M 完成, K 失败" + 总耗时 |

**与 ActivityStrip 的关系：**
现有 `ActivityStrip` 在 `message.streaming` 时显示当前工具名和阶段，位于消息气泡上方。`ToolCallBar` 渲染的是工具调用的持久化记录（已发生的调用列表），两者职责不同：
- `ActivityStrip`：实时流式状态指示器，消息完成后消失
- `ToolCallBar`：工具调用的持久化摘要，始终可见

两者可以共存：`ActivityStrip` 在上方显示实时阶段，`ToolCallBar` 在下方累积工具调用记录。不需要移除 `ActivityStrip`。

**样式规格：**
- 容器：`bg-white border border-slate-200 border-l-[3px] rounded-[10px] shadow-sm`
- 左边框颜色按状态：蓝色（`border-l-blue-500`）/ 绿色（`border-l-emerald-500`）/ 橙色（`border-l-amber-500`）
- 内间距：`px-3.5 py-2.5`
- 点击时整体显示 hover 阴影效果，表示可交互
- 右侧显示箭头图标（`ChevronRight`）

**触发条件：**
当 `toolCallGroups` 存在且非空时，渲染 `ToolCallBar` 替代当前的 `toolCallGroups.map(...)` 循环。

### 2. 工具调用时间线模态框（ToolCallTimelineModal）

点击工具条后弹出模态框，展示纵向时间线链路。

**模态框结构：**

```
┌─────────────────────────────────────────┐
│ 工具调用链路                         ✕  │
│ 共 12 次调用 · 8 成功 · 4 失败 · 2.1s  │
├─────────────────────────────────────────┤
│                                         │
│  ● mcp_peekaboo_list      完成   120ms  │
│  │  Lists system info...                │
│  │                                      │
│  ● mcp_peekaboo_app       完成    85ms  │
│  │  Control applications...             │
│  │                                      │
│  ● mcp_peekaboo_see       失败   200ms  │
│  │  Captures a screenshot...            │
│  │  ┌─ 错误: Permission denied ──┐     │
│  │  └───────────────────────────┘      │
│  │                                      │
│  ● ...                                  │
│                                         │
└─────────────────────────────────────────┘
```

**模态框头部：**
- 标题："工具调用链路"
- 摘要："{总数} 次调用 · {成功数} 成功 · {失败数} 失败 · {总耗时}"
- 关闭按钮（右上角 ✕）

**时间线节点：**
- 左侧圆点：绿色（成功）/ 红色（失败）/ 蓝色+脉冲动画（执行中）
- 纵向连线：`border-left: 2px solid #e2e8f0`
- 每个节点一行：工具名（`font-weight: 500`）+ 状态标签 + 耗时
- 描述文字（`description`）：灰色小字，截断为一行
- 可点击展开参数和结果详情

**节点展开详情：**
- 参数区：灰色背景代码块
- 结果区：成功为灰色背景，失败为红色背景
- 内容截断阈值与现有 `ToolCallCard` 一致（2000 字符）

**模态框交互：**
- 点击遮罩层关闭
- 点击 ✕ 按钮关闭
- ESC 键关闭
- 弹出动画：`scale(0.95) → scale(1)` + `translateY(10px) → translateY(0)`，200ms ease-out
- 模态框最大高度 `80vh`，内容区可滚动

### 3. 耗时计算

当前数据模型中 `ToolCallInfo` 没有耗时字段。需要在前端通过时间戳差值计算：

- 在 `chatStore.addToolCall` 时记录 `startTime: Date.now()`
- 在 `chatStore.updateToolResult` 时计算 `duration = Date.now() - startTime`
- 新增 `ToolCallInfo.startTime?: number` 和 `ToolCallInfo.duration?: number` 字段
- 总耗时 = 最后一个 `updateToolResult` 的时间 - 第一个 `addToolCall` 的时间

## 组件架构

### 新增组件

| 组件 | 文件路径 | 职责 |
|------|---------|------|
| `ToolCallBar` | `components/ToolCallBar.tsx` | 工具条标签，替代多个 `ToolCallGroup` |
| `ToolCallTimelineModal` | `components/ToolCallTimelineModal.tsx` | 模态框 + 时间线内容 |

### 修改组件

| 组件 | 修改内容 |
|------|---------|
| `MessageBubble.tsx` | 将 `toolCallGroups.map(...)` 替换为 `<ToolCallBar>` |
| `chatStore.ts` | `ToolCallInfo` 新增 `startTime` / `duration` 字段 |

### 保留组件（不修改）

| 组件 | 原因 |
|------|------|
| `ToolCallGroup.tsx` | 可能被 Plan 模式或其他地方引用，暂时保留 |
| `ToolCallCard.tsx` | 时间线模态框内节点展开详情可复用其 `DetailBlock` |
| `StepGroupedTools` | Plan 模式展示保持不变 |

## 数据流

```
WebSocket事件 → chatStore.addToolCall(记录startTime)
                         ↓
            chatStore.updateToolResult(计算duration)
                         ↓
          MessageBubble 检测 toolCalls
                         ↓
              ┌─ showStepView → StepGroupedTools (Plan模式，不变)
              └─ 否 → ToolCallBar (新组件)
                         ↓ 点击
              ToolCallTimelineModal (新组件)
                         ↓ 展示
              时间线节点列表 (工具名 + 状态 + 耗时)
                         ↓ 点击节点
              展开参数/结果详情
```

## 边界情况

1. **只有 1 个工具调用**：仍然渲染 `ToolCallBar`（显示"已完成 1 个工具调用"），保持一致性
2. **工具调用全部失败**：左边框红色，显示"N 个调用全部失败"
3. **流式执行中**：工具条实时更新当前工具名和已完成计数（从 `chatStore` 状态中派生）
4. **Plan 模式**：完全不受影响，走 `showStepView` 分支
5. **无描述的工具**：时间线节点不显示描述行
6. **超长工具名**：工具条和时间线节点中截断（`truncate`）
7. **暗色模式**：工具条和模态框都需要适配暗色主题

## 测试策略

- 工具条在不同状态（执行中/完成/有失败）下的渲染
- 模态框打开/关闭交互
- 时间线节点展开/折叠
- Plan 模式下不渲染工具条（回归测试）
- 暗色模式下视觉正确
- 单个工具调用的边界情况
- 大量工具调用（50+）下模态框滚动性能
