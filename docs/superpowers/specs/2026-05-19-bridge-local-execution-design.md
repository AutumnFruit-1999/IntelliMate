# Bridge 本地执行节点设计

> 将资源管理（服务器）与本地执行能力（用户机器）拆分，通过 WebSocket Bridge 实现 Agent 透明地调用用户本地文件系统和命令。

## 背景与目标

### 问题

当前 `intellimate-gateway` 同时承载"管理平台"和"Agent 执行"两种职责。部署到服务器后，Agent 的内置工具（`readFile`、`writeFile`、`editFile`、`exec`）只能操作服务器文件系统，无法触及用户本地机器。将整个 gateway 跑在本地又太重（需要 MySQL、全部依赖等）。

### 目标

1. **服务器**继续承载管理平台（Web UI、数据库、Agent 大脑、模型管理等）
2. **用户本地**运行一个极致轻量的执行节点，提供文件操作、命令执行、本地 MCP 工具代理
3. Gateway 内部自动路由：Agent 无感知，同一个 `readFile` 工具根据配置在服务器或本地执行
4. 通信协议轻量、快速，不使用 MCP 协议

### 非目标

- 不重构 Agent 执行引擎
- 不改变现有工具的 API 定义
- 不引入新的 AI/LLM 逻辑到本地组件

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│  服务器端 Gateway                                               │
│                                                                 │
│  Web UI ──► Gateway ──► AgentRuntime ──► ToolsEngine            │
│                  │                           │                  │
│                  │         ┌─────────────────┤                  │
│                  │         │                 │                  │
│                  │    内置工具(服务器)    BridgeToolRouter        │
│                  │                           │                  │
│                  │    WebSocket /api/bridge ──┘                  │
│                  │         ▲                                    │
└──────────────────│─────────│────────────────────────────────────┘
                   │         │
                   │    WebSocket（本地主动连接）
                   │         │
┌──────────────────│─────────│────────────────────────────────────┐
│  用户本地         │         │                                    │
│  (npx intellimate-local)   │                                    │
│                            │                                    │
│  Bridge Client ────────────┘                                    │
│    ├── 文件操作：readFile / writeFile / editFile / listFiles     │
│    ├── 命令执行：exec（支持流式输出）                              │
│    └── MCP 代理：连接本地 MCP 服务器，转发调用                     │
└─────────────────────────────────────────────────────────────────┘
```

**设计决策：**

- 本地组件是 **WebSocket 客户端**，主动连接到 gateway（适应任何网络拓扑）
- 通信协议为 **纯 JSON over WebSocket**（轻量、快速、无额外协议开销）
- Agent 看到的工具名**不变**（`readFile` 就是 `readFile`），路由在 gateway 的 `ToolsEngine` 内部完成
- 对 Agent 完全**透明**

## 通信协议

所有消息为 JSON 格式，通过 WebSocket 帧传输。

### 连接与注册

本地组件启动后，通过 WebSocket 连接到 gateway 并注册：

```
连接地址：ws://server:3007/api/bridge/connect?token=<token>
```

注册消息（本地 → Gateway）：

```json
{
  "type": "register",
  "name": "my-laptop",
  "tools": ["readFile", "writeFile", "editFile", "exec", "listFiles"],
  "mcpTools": [
    { "server": "browser", "tools": ["navigate", "screenshot"] },
    { "server": "postgres", "tools": ["query"] }
  ]
}
```

确认消息（Gateway → 本地）：

```json
{
  "type": "registered",
  "nodeId": "uuid-xxx"
}
```

### 工具调用

Gateway 向本地发送工具执行请求（Gateway → 本地）：

```json
{
  "type": "tool_call",
  "id": "req-001",
  "tool": "readFile",
  "args": { "path": "/Users/user/project/main.py" }
}
```

本地返回结果（本地 → Gateway）：

```json
{
  "type": "tool_result",
  "id": "req-001",
  "success": true,
  "result": "import os\n..."
}
```

错误返回：

```json
{
  "type": "tool_result",
  "id": "req-001",
  "success": false,
  "error": "File not found: /Users/user/project/main.py"
}
```

### exec 流式输出

exec 工具支持流式输出（实时显示命令执行过程）：

```json
// Gateway → 本地
{ "type": "tool_call", "id": "req-002", "tool": "exec",
  "args": { "command": "npm test", "cwd": "/Users/user/project" } }

// 本地 → Gateway（多次）
{ "type": "tool_stream", "id": "req-002", "chunk": "Running tests...\n" }
{ "type": "tool_stream", "id": "req-002", "chunk": "✓ 15 tests passed\n" }

// 本地 → Gateway（最终结果）
{ "type": "tool_result", "id": "req-002", "success": true,
  "result": "All tests passed", "exitCode": 0 }
```

### MCP 代理调用

通过 Bridge 转发到本地 MCP 服务器：

```json
// Gateway → 本地
{ "type": "tool_call", "id": "req-003",
  "tool": "mcp:browser:navigate",
  "args": { "url": "https://example.com" } }

// 本地 → Gateway
{ "type": "tool_result", "id": "req-003", "success": true,
  "result": "Page loaded" }
```

### 心跳

保活机制，检测连接健康状态：

```json
{ "type": "ping" }
{ "type": "pong" }
```

间隔：30s 一次。连续 3 次无响应则视为断线。

## Gateway 侧改动

### 新增 bridge 包

```
intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/
├── BridgeWebSocketHandler.java     # /api/bridge/connect WebSocket 端点
├── BridgeNodeRegistry.java         # 管理已连接的 Bridge 节点（内存注册表）
├── BridgeNodeSession.java          # 单个节点会话：WebSocket + 注册信息 + 状态
├── BridgeToolRouter.java           # 工具路由：判断走本地 Bridge 还是服务器内置
├── BridgeToolCallback.java         # 代理 ToolCallback：通过 Bridge 转发工具调用
├── BridgeProtocol.java             # JSON 消息类型定义（register, tool_call 等）
└── BridgeController.java           # REST：查看/管理节点状态
```

### ToolsEngine 改动

核心改动在 `ToolsEngine.getToolCallbacksFor()` 方法中，增加 Bridge 路由逻辑：

```java
// 伪代码
List<ToolCallback> callbacks = mergeBuiltinDynamicMcp(...);

String bridgeNode = agentConfig.getBridgeNode();
if (bridgeNode != null && bridgeRegistry.isConnected(bridgeNode)) {
    BridgeNodeSession session = bridgeRegistry.getSession(bridgeNode);

    // 将可桥接的内置工具替换为 BridgeToolCallback
    for (String tool : session.getRegisteredTools()) {
        callbacks.replaceIf(
            cb -> cb.getName().equals(tool),
            new BridgeToolCallback(tool, session)
        );
    }

    // 添加本地 MCP 代理工具
    for (McpToolInfo mcpTool : session.getMcpTools()) {
        callbacks.add(new BridgeToolCallback(
            "mcp:" + mcpTool.server() + ":" + mcpTool.name(),
            session
        ));
    }
}
```

### 数据库迁移 V26

```sql
CREATE TABLE bridge_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    token_hash VARCHAR(128) NOT NULL,
    last_connected_at DATETIME,
    last_heartbeat_at DATETIME,
    status VARCHAR(16) DEFAULT 'DISCONNECTED',
    registered_tools JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

ALTER TABLE agent ADD COLUMN bridge_node VARCHAR(64) DEFAULT NULL;
```

### Agent 配置扩展

`AgentConfigService.resolve()` 增加 `bridgeNode` 字段的解析，传递到 `AgentRunRequest`。

`ResolvedAgentConfig` 新增 `bridgeNode` 属性。

### REST API

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/bridge/nodes` | GET | 列出所有 Bridge 节点及状态 |
| `/api/bridge/nodes/{name}` | GET | 查看单个节点详情 |
| `/api/bridge/nodes/{name}` | DELETE | 移除节点（断开连接 + 删除记录） |
| `/api/bridge/nodes` | POST | 创建节点（生成 token） |

## 本地组件：intellimate-local

### npm 包

- 包名：`intellimate-local`
- 分发：npm 公共仓库，支持 `npx intellimate-local` 零安装运行
- 入口：`bin` 字段指向编译后的 CLI

### 项目结构

```
intellimate-local/
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts              # CLI 入口：解析参数、启动
│   ├── bridge-client.ts      # WebSocket 客户端：连接、收发、自动重连
│   ├── tool-executor.ts      # 工具分发：根据 tool 名路由到对应执行器
│   ├── tools/
│   │   ├── file-ops.ts       # readFile, writeFile, editFile, listFiles
│   │   └── exec.ts           # 命令执行，支持流式输出
│   ├── mcp-proxy/
│   │   └── proxy.ts          # 连接本地 MCP Server，代理工具调用
│   └── config.ts             # 配置加载（CLI args + YAML 文件）
└── config.example.yml
```

### 依赖

| 依赖 | 用途 | 必要性 |
|------|------|--------|
| `ws` | WebSocket 客户端 | 必须 |
| `yaml` | 配置文件解析 | 必须 |
| `@modelcontextprotocol/sdk` | MCP 代理功能 | 可选（仅 MCP 代理需要） |

### 工具实现

| 工具 | 参数 | 功能 |
|------|------|------|
| `readFile` | `path: string` | 读取本地文件内容 |
| `writeFile` | `path: string, content: string` | 写入文件（覆盖或新建） |
| `editFile` | `path: string, oldText: string, newText: string` | 精确文本替换 |
| `listFiles` | `path: string, pattern?: string, recursive?: boolean` | 列出目录文件 |
| `exec` | `command: string, cwd?: string` | 执行 shell 命令，支持流式输出 |

### 启动方式

```bash
# 最简启动
npx intellimate-local --server ws://your-server:3007/api/bridge/connect --token my-secret

# 带配置文件
npx intellimate-local --config ./config.yml

# 全局安装
npm i -g intellimate-local
intellimate-local --server ws://... --token ...
```

### 配置文件

```yaml
server: "ws://your-server:3007/api/bridge/connect"
token: "my-secret"
name: "my-laptop"

security:
  allowedPaths:
    - "/Users/user/projects"
  blockedCommands:
    - "rm -rf /"

mcpServers:
  - name: browser
    command: npx
    args: ["-y", "@anthropic/mcp-browser"]
  - name: postgres
    command: npx
    args: ["-y", "@anthropic/mcp-postgres", "--dsn", "postgres://localhost/mydb"]
```

### 安全措施

- **Token 认证**：WebSocket 握手时通过 query parameter 校验
- **路径白名单**：可配置 `allowedPaths`，限制文件操作范围
- **命令黑名单**：可配置 `blockedCommands`，阻止危险命令
- **exec 输出限制**：单次命令输出上限（默认 1MB），防止 OOM
- **exec 并发限制**：同时执行的命令数上限（默认 5），防止资源耗尽
- **超时控制**：工具执行超时（默认 60s），超时自动终止

## 错误处理与边界情况

### Bridge 断线

| 场景 | 行为 |
|------|------|
| 本地组件主动断开 | Gateway 标记节点 DISCONNECTED |
| 网络抖动 | 本地组件自动重连（指数退避：1s → 2s → 4s → ... → 30s 上限） |
| Agent 调用时 Bridge 离线 | 返回友好错误提示，不中断 Agent 对话流 |
| 工具执行超时 | 返回超时错误，Agent 可选择重试或告知用户 |

### Agent 感知 Bridge 状态

- Bridge 在线：工具描述动态调整为"读取用户本地机器上的文件"
- Bridge 离线：可桥接工具的描述改为"读取服务器上的文件"，或根据 Agent 配置隐藏
- 这确保 Agent 理解当前的执行上下文

### 并发控制

- 多个 Agent 可同时通过同一 Bridge 执行工具调用
- 每个 tool_call 独立，通过 `id` 匹配请求与响应
- 本地组件并行处理文件操作，串行或受限并行处理 exec 命令

## 多用户扩展路径

当前设计为个人使用，但架构预留多用户扩展：

1. **用户级 Bridge 绑定**：新增 `user_bridge_endpoint` 表，映射 userId → bridge node
2. **会话级 Bridge 解析**：`MessagePipeline` 根据当前用户 session 确定使用哪个 Bridge
3. **多节点管理**：`BridgeNodeRegistry` 已支持多节点并行连接
4. **权限隔离**：每个用户的 Bridge 节点独立，互不干扰

## 实现范围

### 第一阶段（MVP）

- Gateway: bridge 包（WebSocket 端点、节点注册、工具路由）
- Gateway: V26 数据库迁移
- Gateway: Agent 配置 bridgeNode 支持
- 本地组件: bridge-client + 5 个内置工具
- 本地组件: CLI 启动 + 基础配置

### 第二阶段

- 本地组件: MCP 代理功能
- Gateway: Bridge 管理 REST API + Web UI 展示
- Gateway: Bridge 节点健康检查与状态推送
- exec 流式输出端到端打通（本地 → gateway → Web UI）

### 第三阶段

- 多用户 Bridge 绑定
- 安全增强（wss、路径白名单 UI 配置）
- Bridge 节点分组与标签
