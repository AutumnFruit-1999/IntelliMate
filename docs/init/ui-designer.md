# IntelliMate WebChat UI 设计文档

> **版本**: 1.0.0
> **日期**: 2026-03-10
> **参考**: OpenClaw Control UI / WebChat / OpenCami

---

## 1. 设计理念

IntelliMate WebChat 参考 OpenClaw 的 Dashboard + Chat 双面板架构，MVP 阶段聚焦**对话体验**：

- **对话优先**: 以 Chat 面板为核心，侧边栏为辅助导航
- **流式原生**: Agent 回复逐 token 渲染，打字机效果，减少用户等待感
- **响应式**: 桌面双栏、移动端单栏自适应
- **主题适配**: 亮色/暗色主题，跟随系统偏好，支持手动切换
- **本地优先**: 所有数据通过 WebSocket 与本地 Gateway 通信，无第三方依赖

---

## 2. 技术栈

| 层 | 技术 | 版本 | 选择理由 |
|----|------|------|---------|
| 框架 | React + TypeScript | 19.x | 生态丰富，流式渲染友好，类型安全 |
| 构建 | Vite | 6.x | 秒级 HMR，ESM 原生 |
| 样式 | Tailwind CSS | 4.x | 原子化 CSS，快速迭代，暗色主题内建 |
| 状态 | Zustand | 5.x | 轻量、无 boilerplate、React 外可用 |
| Markdown | react-markdown + remark-gfm | — | 渲染 Agent 回复中的富文本 |
| 代码高亮 | react-syntax-highlighter | — | 代码块语法着色 |
| 图标 | lucide-react | — | 轻量 SVG 图标库 |

---

## 3. 页面布局

### 3.1 桌面端 (≥768px)

```
┌──────────────────────────────────────────────────────────┐
│ TopBar: Logo / IntelliMate / ConnectionStatus / ThemeToggle │
├────────────┬─────────────────────────────────────────────┤
│            │                                             │
│  Sidebar   │              ChatPanel                      │
│  (240px)   │                                             │
│            │  ┌─────────────────────────────────────┐    │
│  Sessions  │  │         MessageList                 │    │
│  List      │  │  (auto-scroll, virtual scroll)      │    │
│            │  │                                     │    │
│  ───────── │  │  [user] Hello                       │    │
│  /help     │  │  [assistant] Hi there! ...█         │    │
│  /reset    │  │                                     │    │
│  /status   │  └─────────────────────────────────────┘    │
│            │  ┌─────────────────────────────────────┐    │
│            │  │ ComposeArea: [input] [Send]          │    │
│            │  └─────────────────────────────────────┘    │
├────────────┴─────────────────────────────────────────────┤
│ StatusBar: ws://localhost:3007 · Session: abc · Agent: x │
└──────────────────────────────────────────────────────────┘
```

### 3.2 移动端 (<768px)

- Sidebar 默认隐藏，通过汉堡菜单展开为 overlay
- ChatPanel 占满宽度
- ComposeArea 固定在底部
- TopBar 精简：Logo + 连接状态 + 菜单按钮

---

## 4. 核心组件规格

### 4.1 TopBar

| 元素 | 描述 |
|------|------|
| Logo | IntelliMate 图标 + 文字 |
| ConnectionStatus | 绿点=已连接 / 黄点=重连中 / 红点=断开 |
| ThemeToggle | 亮色/暗色切换按钮 (Sun/Moon 图标) |
| MobileMenuBtn | 移动端：汉堡按钮打开 Sidebar |

### 4.2 Sidebar

| 元素 | 描述 |
|------|------|
| 新对话按钮 | 发送 `/reset` 清空历史 |
| 会话信息 | 当前 session ID、agent 名称 |
| 快捷命令 | `/help`, `/reset`, `/status`, `/model` 按钮组 |
| 连接详情 | WebSocket URL、认证状态 |

### 4.3 MessageList

- 渲染所有消息（用户 + 助手）
- 新消息到来时自动滚动到底部
- 用户手动上滚时暂停自动滚动，出现"回到底部"浮动按钮
- 消息按时间正序排列

### 4.4 MessageBubble

| 角色 | 样式 |
|------|------|
| user | 右对齐，主色背景，白色文字 |
| assistant | 左对齐，浅灰背景 (暗色模式: 深灰背景) |

内容渲染：
- 纯文本 → 直接显示
- Markdown → react-markdown 渲染 (支持 GFM: 表格、任务列表、删除线)
- 代码块 → react-syntax-highlighter 高亮 + 复制按钮
- 流式中 → 末尾显示闪烁光标 `█`

### 4.5 ComposeArea

| 元素 | 描述 |
|------|------|
| TextArea | 自适应高度 (1-5 行)，Enter 发送，Shift+Enter 换行 |
| SendButton | 发送图标按钮，输入为空时禁用 |
| StopButton | Agent 回复中显示，点击取消（发送 `/reset`） |
| CommandPopup | 输入 `/` 时弹出命令列表，上下键选择，Enter 确认 |

### 4.6 StreamingText

流式文字渲染组件：
- 接收 `chunks: string[]`，拼接后用 react-markdown 渲染
- 流式中末尾追加 CSS 动画光标
- 完成后移除光标

### 4.7 ConnectionStatus

状态机：

```
CONNECTING → CONNECTED → (DISCONNECTED → RECONNECTING → CONNECTED)
```

| 状态 | 视觉 |
|------|------|
| CONNECTING | 黄色脉冲点 + "连接中..." |
| CONNECTED | 绿色实点 + "已连接" |
| DISCONNECTED | 红色实点 + "已断开" |
| RECONNECTING | 黄色脉冲点 + "重连中 (n/5)..." |

---

## 5. WebSocket 协议对接

### 5.1 连接

```
WebSocket URL: ws://{host}:{port}/ws?token={authToken}
默认: ws://localhost:3007/ws
```

认证方式（二选一）：
- 查询参数: `?token=xxx`
- HTTP 头: `Authorization: Bearer xxx`

### 5.2 帧类型

#### EventFrame (服务端 → 客户端)

```json
{
  "type": "event",
  "event": "<event-name>",
  "payload": { ... },
  "seq": 123
}
```

#### RequestFrame (客户端 → 服务端)

```json
{
  "type": "request",
  "id": "<uuid>",
  "method": "<method-name>",
  "params": { ... }
}
```

#### ResponseFrame (服务端 → 客户端)

```json
{
  "type": "response",
  "id": "<request-id>",
  "ok": true,
  "payload": { ... },
  "error": null
}
```

### 5.3 服务端事件

| 事件 | 触发时机 | payload |
|------|---------|---------|
| `session.welcome` | 连接成功后 | `{ wsSessionId }` |
| `ping` | 每 30 秒 | `{}` |
| `agent.chunk` | 流式 token | `{ text, requestId }` |
| `agent.done` | 回复完成 | `{ text, requestId }` |

### 5.4 客户端请求

#### conversation.message

```json
{
  "type": "request",
  "id": "req-001",
  "method": "conversation.message",
  "params": {
    "text": "用户输入内容",
    "channelId": "webchat",
    "contextType": "dm",
    "contextId": "可选，默认用 wsSessionId"
  }
}
```

#### 心跳回复

收到 `ping` 事件后，客户端发送：

```json
{
  "type": "event",
  "event": "pong",
  "payload": {},
  "seq": null
}
```

### 5.5 斜杠命令

以 `/` 开头的文本作为 `conversation.message` 的 `text` 发送，服务端自动识别并处理。

| 命令 | 参数 | 功能 |
|------|------|------|
| `/reset` | 无 | 清空会话历史 |
| `/status` | 无 | 返回会话状态 |
| `/model <name>` | 模型名 | 切换模型 |
| `/approve <code>` | 配对码 | 批准 DM 配对 |
| `/help` | 无 | 显示命令列表 |

---

## 6. 交互流程

### 6.1 连接流程

```
1. 页面加载 → 创建 WebSocket 连接
2. 收到 session.welcome → 存储 wsSessionId，显示"已连接"
3. 心跳循环: 收到 ping → 立即回复 pong
4. 连接断开 → 指数退避重连 (1s, 2s, 4s, 8s, 16s, max 30s)
5. 重连成功 → 恢复状态
```

### 6.2 发送消息流程

```
1. 用户在 ComposeArea 输入文本，按 Enter
2. 创建 RequestFrame { method: "conversation.message", params: { text } }
3. 在 MessageList 立即追加用户消息气泡
4. 创建空的 assistant 消息气泡（显示"思考中..."占位）
5. 收到 agent.chunk → 追加 token 到当前 assistant 消息
6. 收到 agent.done → 标记消息完成，移除光标动画
7. 收到 ResponseFrame → 确认完成
```

### 6.3 命令输入流程

```
1. 用户输入 "/" → 弹出 CommandPopup
2. 继续输入过滤命令列表
3. 上下键选择 → Enter 确认
4. 发送命令作为 conversation.message
5. 收到 ResponseFrame → 显示命令结果
```

---

## 7. 色彩与主题

### 7.1 亮色主题

| 元素 | 色值 |
|------|------|
| 背景 | `#ffffff` |
| 侧栏背景 | `#f8fafc` |
| 用户消息背景 | `#3b82f6` (blue-500) |
| 用户消息文字 | `#ffffff` |
| 助手消息背景 | `#f1f5f9` (slate-100) |
| 助手消息文字 | `#1e293b` (slate-800) |
| 边框 | `#e2e8f0` (slate-200) |
| 主色 | `#3b82f6` (blue-500) |

### 7.2 暗色主题

| 元素 | 色值 |
|------|------|
| 背景 | `#0f172a` (slate-900) |
| 侧栏背景 | `#1e293b` (slate-800) |
| 用户消息背景 | `#2563eb` (blue-600) |
| 用户消息文字 | `#ffffff` |
| 助手消息背景 | `#334155` (slate-700) |
| 助手消息文字 | `#f1f5f9` (slate-100) |
| 边框 | `#334155` (slate-700) |
| 主色 | `#60a5fa` (blue-400) |

---

## 8. 项目结构

```
intellimate-web/              # 独立前端项目
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx           # 入口
│   ├── App.tsx            # 根组件：路由 + 主题 + 布局
│   ├── hooks/
│   │   └── useWebSocket.ts    # WS 连接/重连/心跳/消息分发
│   ├── stores/
│   │   └── chatStore.ts       # Zustand: 消息、连接状态、streaming buffer
│   ├── components/
│   │   ├── ChatPanel.tsx      # 主聊天面板（组合 MessageList + ComposeArea）
│   │   ├── MessageList.tsx    # 消息列表
│   │   ├── MessageBubble.tsx  # 消息气泡（Markdown 渲染）
│   │   ├── ComposeArea.tsx    # 输入框 + 发送/停止按钮
│   │   ├── StreamingText.tsx  # 流式文字 + 光标动画
│   │   ├── ConnectionStatus.tsx # 连接状态
│   │   ├── Sidebar.tsx        # 侧栏
│   │   ├── TopBar.tsx         # 顶部栏
│   │   └── CommandPopup.tsx   # 斜杠命令弹出面板
│   ├── lib/
│   │   ├── protocol.ts        # 帧类型 TS 定义
│   │   └── wsClient.ts        # WebSocket 客户端类
│   └── styles/
│       └── globals.css        # Tailwind + 自定义 CSS
```

---

## 9. 后端配合

### 9.1 CORS 配置

前端 dev server 运行在 `localhost:5173`，需要后端 Gateway 允许跨域 WebSocket 连接。

在 `WebSocketRouterConfig.java` 中配置 `setAllowedOrigins("*")` 或指定 dev server origin。

### 9.2 静态资源服务（生产模式）

生产环境可将 `intellimate-web` 构建产物（`dist/`）作为 Gateway 的静态资源服务，
通过 WebFlux 的 `RouterFunction` 或 Spring 资源处理器提供，实现前后端同端口部署。
