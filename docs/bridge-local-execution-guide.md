# IntelliMate Bridge - 本地执行桥接功能文档

> 让部署在远程服务器的 Agent 透明地访问用户本地机器的文件系统和 Shell 环境。

---

## 目录

1. [功能概述](#1-功能概述)
2. [架构总览](#2-架构总览)
3. [服务端（Gateway）](#3-服务端gateway)
4. [客户端（intellimate-local）](#4-客户端intellimate-local)
5. [服务端与客户端对接](#5-服务端与客户端对接)
6. [WebSocket 通信协议](#6-websocket-通信协议)
7. [安全机制](#7-安全机制)
8. [故障排查](#8-故障排查)
9. [扩展：添加新工具](#9-扩展添加新工具)
10. [未来扩展 TODO](#10-未来扩展-todo)
11. [项目结构参考](#11-项目结构参考)

---

## 1. 功能概述

### 解决的问题

IntelliMate 部署在远程服务器时，Agent 只能访问服务器本地的文件和命令。用户希望 Agent 能操作自己电脑上的文件（读写代码、执行 `git`/`npm` 等命令），但又不想把整个项目搬到本地运行。

### 解决方案

**Bridge（桥接）** 架构将功能拆为两个组件：

| 组件 | 部署位置 | 职责 |
|------|----------|------|
| **Gateway（服务端）** | 远程服务器 | Web UI、Agent 调度、对话管理、模型调用 |
| **intellimate-local（客户端）** | 用户电脑 | 文件操作、Shell 命令执行 |

两者通过 **WebSocket** 长连接通信。Agent 调用工具时，Gateway 自动判断是在服务器执行还是转发到用户本地——对 Agent 完全透明。

### 支持的本地工具

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `readFile` | 读取文件内容 | `path`, `startLine?`, `lineCount?` |
| `writeFile` | 写入文件 | `path`, `content` |
| `editFile` | 精确文本替换 | `path`, `oldText`, `newText` |
| `listFiles` | 列出目录内容 | `path`, `pattern?`, `recursive?` |
| `exec` | 执行 Shell 命令 | `command`, `workingDirectory?`, `timeoutSeconds?` |

---

## 2. 架构总览

```
┌──────────────────────────────────────────┐
│        远程服务器 (Gateway)                │
│                                          │
│  浏览器/Web UI ─── REST/WS API           │
│       │                                  │
│  MessagePipeline → AgentRuntime          │
│                       │                  │
│              ToolsEngine (路由层)         │
│              ┌────────┴────────┐         │
│        服务端工具          Bridge 转发     │
│    (无 bridgeNode)    (有 bridgeNode)    │
│                            │             │
│              BridgeWebSocketHandler       │
│              /api/bridge/connect          │
└──────────────────┬───────────────────────┘
                   │ WebSocket (JSON)
                   │ 由客户端主动发起连接
┌──────────────────┴───────────────────────┐
│        用户电脑 (intellimate-local)        │
│                                          │
│  BridgeClient (WebSocket 客户端)          │
│       │                                  │
│  ToolExecutor (安全校验 + 分发)           │
│       │                                  │
│  ┌────┴───────────────────┐              │
│  file-ops.ts     exec.ts                 │
│  (文件读写)      (Shell 命令)             │
└──────────────────────────────────────────┘
```

### 核心流程

1. 用户电脑运行 `intellimate-local`，**主动连接**到服务端 Gateway 的 WebSocket 端点
2. 连接成功后，客户端注册自身名称和可用工具列表
3. 管理员在 Gateway 上将某个 Agent 绑定到该客户端节点
4. 用户通过 Web UI 向该 Agent 发送消息
5. Agent 需要调用工具时，`ToolsEngine` 检测到 Agent 绑定了 Bridge 节点
6. 匹配的工具调用通过 WebSocket 转发到用户电脑执行
7. 执行结果通过 WebSocket 返回给 Gateway，再传回 Agent

---

## 3. 服务端（Gateway）

### 3.1 环境要求

- Java 21+
- MySQL 8.0+
- Maven 3.9+

### 3.2 构建与启动

```bash
# 进入项目根目录
cd /path/to/IntelliMate

# 编译安装所有模块（含 Bridge 相关代码）
mvn install -DskipTests

# 启动 Gateway
mvn spring-boot:run -pl intellimate-gateway

# 或使用打好的 jar
java -jar intellimate-gateway/target/intellimate-gateway-0.0.1-SNAPSHOT.jar
```

### 3.3 配置

Gateway 通过环境变量或 `application.yml` 配置：

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `server.port` / `INTELLIMATE_PORT` | Gateway 监听端口 | `3007` |
| `spring.r2dbc.url` | 数据库连接 | `r2dbc:mysql://localhost:3306/intellimate` |
| `server.address` | 监听地址（外部访问需改为 `0.0.0.0`） | `0.0.0.0` |

> **重要：** 如果客户端从外网或其他机器连接，确保 Gateway 监听在 `0.0.0.0` 且防火墙放行端口。

### 3.4 数据库表

Gateway 启动时通过 Flyway 自动创建 `bridge_node` 表（迁移文件 `V26__bridge_node.sql`）：

```sql
CREATE TABLE bridge_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE COMMENT '节点名称',
    token_hash VARCHAR(128) NOT NULL COMMENT '认证 token 的 SHA-256 散列',
    status VARCHAR(16) NOT NULL DEFAULT 'DISCONNECTED',
    registered_tools JSON DEFAULT NULL COMMENT '节点注册的可用工具',
    last_connected_at DATETIME DEFAULT NULL,
    last_heartbeat_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- agent 表新增字段
ALTER TABLE agent ADD COLUMN bridge_node VARCHAR(64) DEFAULT NULL COMMENT '绑定的 Bridge 节点名称';
```

### 3.5 REST API

#### 3.5.1 创建 Bridge 节点

创建一个节点并获取认证 token（**token 仅返回一次**）。

```bash
curl -X POST http://<gateway-host>:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -d '{"name": "my-laptop"}'
```

响应：

```json
{
  "id": 1,
  "name": "my-laptop",
  "token": "6db15171512641279b59272d084efade",
  "message": "请保存此 token，它只显示一次。"
}
```

#### 3.5.2 查看所有节点

```bash
curl http://<gateway-host>:3007/api/bridge/nodes
```

响应：

```json
[
  {
    "id": 1,
    "name": "my-laptop",
    "status": "CONNECTED",
    "registeredTools": "[\"readFile\", \"writeFile\", \"editFile\", \"exec\", \"listFiles\"]",
    "lastConnectedAt": "2026-05-19T12:30:00",
    "lastHeartbeatAt": "2026-05-19T12:35:00"
  }
]
```

#### 3.5.3 删除节点

```bash
curl -X DELETE http://<gateway-host>:3007/api/bridge/nodes/my-laptop
```

#### 3.5.4 绑定 Agent 到 Bridge 节点

```bash
# 绑定
curl -X PUT http://<gateway-host>:3007/api/agent/<agent-name> \
  -H 'Content-Type: application/json' \
  -d '{"bridgeNode": "my-laptop"}'

# 解绑
curl -X PUT http://<gateway-host>:3007/api/agent/<agent-name> \
  -H 'Content-Type: application/json' \
  -d '{"bridgeNode": null}'
```

#### 3.5.5 查看 Agent 配置（确认绑定状态）

```bash
curl http://<gateway-host>:3007/api/agent/<agent-name>
```

响应中会包含 `"bridgeNode": "my-laptop"` 字段。

### 3.6 工具路由机制

Gateway 中 `ToolsEngine` 负责工具路由，核心逻辑：

1. 根据 Agent 配置获取可用工具回调数组
2. 如果 Agent 绑定了 `bridgeNode` 且该节点在线：
   - 获取节点注册的工具列表
   - 将匹配的工具回调替换为 `BridgeToolCallback`（通过 WebSocket 转发）
3. 替换后的回调数组贯穿整个 Agent 执行生命周期
4. 不匹配的工具（如 `webFetch`）仍在服务端执行

### 3.7 关闭 Bridge 功能

如果不需要 Bridge 功能：

- 不创建 Bridge 节点，不绑定 Agent 即可
- Bridge 相关代码全部按需加载（`@Autowired(required = false)`），不影响正常功能
- 取消已绑定的 Agent：将 `bridgeNode` 设为 `null`

---

## 4. 客户端（intellimate-local）

### 4.1 环境要求

- Node.js >= 18
- 网络可达 Gateway 的 WebSocket 端口

### 4.2 安装与构建

```bash
# 方式一：从项目源码构建
cd intellimate-local
npm install
npm run build

# 方式二：未来发布到 npm 后直接使用
npx intellimate-local --server ws://... --token ... --name ...
```

### 4.3 启动命令

```bash
node dist/index.js \
  --server ws://<gateway-host>:3007/api/bridge/connect \
  --token <创建节点时获取的 token> \
  --name <节点名称>
```

**参数说明：**

| 参数 | 必填 | 说明 |
|------|------|------|
| `--server` | 是 | Gateway WebSocket 地址 |
| `--token` | 是 | 创建节点时获取的认证 token |
| `--name` | **是** | 节点名称，**必须**与 Gateway 创建节点时的名称完全一致 |
| `--config` | 否 | YAML 配置文件路径（替代命令行参数） |

**成功连接的输出：**

```
[intellimate-local] Node "my-laptop" connecting to ws://192.168.1.100:3007/api/bridge/connect
[bridge] Connected to gateway
[bridge] Registered as node: 1
```

### 4.4 配置文件（可选）

对于长期使用，推荐使用 YAML 配置文件代替命令行参数：

```yaml
# config.yml
server: "ws://192.168.1.100:3007/api/bridge/connect"
token: "6db15171512641279b59272d084efade"
name: "my-laptop"

security:
  allowedPaths:          # 限制可访问的路径（不配置则不限制）
    - "/Users/user/projects"
    - "/tmp"
  blockedCommands:       # 禁止的命令模式（支持通配符）
    - "rm -rf /"
    - "sudo *"
    - "shutdown*"
```

使用配置文件启动：

```bash
node dist/index.js --config ./config.yml
```

### 4.5 客户端工作流程

1. **解析配置** → 从 CLI 参数或 YAML 文件加载 server/token/name
2. **建立连接** → 通过 WebSocket 连接到 Gateway，URL 携带 token 参数
3. **注册节点** → 发送 `register` 消息，上报节点名和可用工具列表
4. **等待调用** → 进入消息循环，监听 Gateway 发来的 `tool_call` 消息
5. **执行工具** → 收到调用后由 `ToolExecutor` 分发到对应的工具实现
6. **返回结果** → 将执行结果通过 `tool_result` 消息发回 Gateway
7. **心跳保活** → 每 30 秒响应 Gateway 的 `ping` 消息
8. **断线重连** → 连接断开后指数退避重试（1s → 2s → 4s → ... → 30s 上限）

### 4.6 客户端不需要什么

客户端**极度轻量**，不需要：

- 数据库
- Web 服务器
- 模型 API Key
- Java 运行时
- 任何 IntelliMate 的其他组件

只需要 **Node.js** 和 **到 Gateway 的网络连接**。

---

## 5. 服务端与客户端对接

### 5.1 操作分类：一次性 vs 日常

| 操作 | 频率 | 在哪里执行 |
|------|------|-----------|
| 启动 Gateway | 部署时一次 | 服务器 |
| 创建 Bridge 节点（获取 token） | 每个节点一次 | 任意机器（调 API） |
| 绑定 Agent 到节点 | 每个 Agent 一次 | 任意机器（调 API） |
| 构建 `intellimate-local`（npm install + build） | 首次一次 | 用户电脑 |
| **启动 Bridge 客户端连接** | **每次使用时** | **用户电脑** |

> **日常操作只有一步**：在用户电脑上运行 `node dist/index.js --server ... --token ... --name ...`

### 5.2 首次完整配置（从零开始）

以下步骤只需要做一次：

#### 步骤 A：确保 Gateway 运行（服务器上操作）

```bash
cd /path/to/IntelliMate
mvn install -DskipTests
mvn spring-boot:run -pl intellimate-gateway
```

确认 Gateway 可访问：

```bash
# 从客户端机器测试
curl http://<gateway-ip>:3007/api/bridge/nodes
# 应返回 [] （空数组）
```

#### 步骤 B：创建 Bridge 节点（任意机器上操作）

```bash
curl -s -X POST http://<gateway-ip>:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -d '{"name": "my-laptop"}'
```

响应示例：

```json
{
  "id": 1,
  "name": "my-laptop",
  "token": "6db15171512641279b59272d084efade",
  "command": "npx intellimate-local --server ws://172.18.210.245:3007/api/bridge/connect --token 6db15171512641279b59272d084efade --name my-laptop",
  "message": "请保存此 token，它只显示一次。连接命令：..."
}
```

> **重要：** 请保存 `token` 和 `command` 值。token 只显示这一次！
> `command` 字段包含可直接复制使用的连接命令（地址根据你的访问来源自动填入）。

#### 步骤 C：绑定 Agent（任意机器上操作）

```bash
curl -s -X PUT http://<gateway-ip>:3007/api/agent/<agent-name> \
  -H 'Content-Type: application/json' \
  -d '{"bridgeNode": "my-laptop"}'
```

#### 步骤 D：构建本地组件（用户电脑上操作，只需一次）

```bash
cd intellimate-local
npm install
npm run build
```

### 5.3 日常使用（每次连接）

完成首次配置后，每次使用只需在用户电脑上执行一条命令：

```bash
cd intellimate-local
node dist/index.js \
  --server ws://<gateway-ip>:3007/api/bridge/connect \
  --token <步骤B获取的token> \
  --name my-laptop
```

或者直接使用步骤 B 返回的 `command` 字段值（如果本地组件已发布到 npm）：

```bash
npx intellimate-local --server ws://172.18.210.245:3007/api/bridge/connect --token 6db15171512641279b59272d084efade --name my-laptop
```

**成功输出：**

```
[intellimate-local] Node "my-laptop" connecting to ws://172.18.210.245:3007/api/bridge/connect
[bridge] Connected to gateway
[bridge] Registered as node: 1
```

保持终端窗口不关闭即可。关闭终端或按 `Ctrl+C` 停止连接。

### 5.4 验证 Bridge 工作

```bash
# 检查节点状态
curl http://<gateway-ip>:3007/api/bridge/nodes
# 应显示 status: "CONNECTED"
```

在 Web UI 中用已绑定的 Agent 发消息测试：
- "请执行 pwd 命令" → 应返回**客户端机器**的路径
- "请读取 /tmp/test.txt" → 应读取**客户端机器**的文件

### 5.5 一键从零连接（高级用法）

如果已有节点和 token，只需一条命令：

```bash
node dist/index.js \
  --server ws://<gateway-ip>:3007/api/bridge/connect \
  --token <token> \
  --name <node-name>
```

如果要从零创建节点 + 绑定 Agent + 连接（一条命令完成所有操作）：

```bash
TOKEN=$(curl -s -X POST http://<gateway-ip>:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -d '{"name": "<node-name>"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])") \
  && curl -s -X PUT http://<gateway-ip>:3007/api/agent/<agent-name> \
  -H 'Content-Type: application/json' \
  -d '{"bridgeNode": "<node-name>"}' \
  && node dist/index.js \
  --server ws://<gateway-ip>:3007/api/bridge/connect \
  --token $TOKEN \
  --name <node-name>
```

### 5.3 多节点与多 Agent

- **一个节点可以被多个 Agent 绑定**：多个 Agent 可以共享同一个客户端节点
- **一个 Agent 只能绑定一个节点**：每个 Agent 在任一时刻只指向一个 Bridge 节点
- **不同用户可以有不同节点**：每个用户在自己电脑上运行独立的 `intellimate-local`
- **同一台电脑可以运行多个节点**（使用不同名称和 token，用于不同的安全策略）

### 5.4 连接状态说明

| 状态 | 含义 |
|------|------|
| `CONNECTED` | 客户端 WebSocket 连接正常，可以转发工具调用 |
| `DISCONNECTED` | 客户端离线。Agent 调用工具时会在服务端执行（回退到默认行为） |

当客户端断开时，Agent 不会报错——工具调用会自动回退到服务端执行。只有在客户端在线时，工具调用才会通过 Bridge 转发。

---

## 6. WebSocket 通信协议

### 6.1 连接地址

```
ws://<gateway-host>:<port>/api/bridge/connect?token=<token>
```

- 连接方向：客户端 → 服务端（客户端主动发起）
- 认证方式：URL query parameter 中携带 `token`
- 传输格式：JSON 文本消息

### 6.2 消息类型

所有消息都是 JSON 格式，通过 `type` 字段区分。

#### 注册（客户端 → 服务端）

客户端连接成功后，立即发送注册消息：

```json
{
  "type": "register",
  "name": "my-laptop",
  "tools": ["readFile", "writeFile", "editFile", "exec", "listFiles"],
  "mcpTools": []
}
```

#### 注册确认（服务端 → 客户端）

```json
{
  "type": "registered",
  "nodeId": "1"
}
```

#### 工具调用（服务端 → 客户端）

当 Agent 需要调用本地工具时：

```json
{
  "type": "tool_call",
  "id": "call_abc123",
  "tool": "readFile",
  "args": { "path": "/Users/user/project/main.py" }
}
```

#### 工具结果（客户端 → 服务端）

执行完成后返回结果：

```json
{
  "type": "tool_result",
  "id": "call_abc123",
  "success": true,
  "result": "# main.py\nprint('hello')\n"
}
```

错误情况：

```json
{
  "type": "tool_result",
  "id": "call_abc123",
  "success": false,
  "error": "ENOENT: no such file or directory, open '/Users/user/project/main.py'"
}
```

#### 流式输出（客户端 → 服务端，仅 exec 命令）

```json
{
  "type": "tool_stream",
  "id": "call_abc123",
  "chunk": "Running tests...\nTest 1 passed\n"
}
```

#### 心跳

```json
// 服务端 → 客户端（每 30 秒一次）
{ "type": "ping" }

// 客户端 → 服务端
{ "type": "pong" }
```

#### 错误消息（服务端 → 客户端）

```json
{
  "type": "error",
  "message": "Authentication failed"
}
```

---

## 7. 安全机制

### 7.1 Token 认证

- 每个 Bridge 节点有唯一的认证 token
- 服务端存储 token 的 **SHA-256 哈希**（非明文）
- Token 仅在创建节点时返回一次
- 如果 token 丢失，需删除节点重新创建

### 7.2 路径白名单（客户端配置）

限制文件操作只能访问指定目录：

```yaml
security:
  allowedPaths:
    - "/Users/user/projects"
    - "/tmp"
```

- 不配置 `allowedPaths` 则不限制
- 路径检查基于前缀匹配
- 被拒绝的操作返回 `"Access denied: path not in allowed list"`

### 7.3 命令黑名单（客户端配置）

阻止危险 Shell 命令：

```yaml
security:
  blockedCommands:
    - "rm -rf /"
    - "sudo *"
    - "shutdown*"
    - "reboot*"
```

- 支持通配符 `*`（转换为正则 `.*`）
- 不配置则不阻止任何命令
- 被阻止的命令返回 `"Command blocked by security policy"`

### 7.4 exec 安全限制

- **输出上限：** 单次命令输出最大 1MB，防止内存溢出
- **执行超时：** 默认 30 秒，可通过工具参数 `timeoutSeconds` 调整
- **进程清理：** 超时后自动终止子进程

---

## 8. 故障排查

### 8.1 客户端无法连接

| 症状 | 排查步骤 |
|------|----------|
| `Failed to connect` | 1. `ping <gateway-ip>` 测试网络 <br> 2. `curl http://<gateway-ip>:3007/api/bridge/nodes` 测试 HTTP 连通 <br> 3. 检查防火墙是否放行端口 |
| `Unknown bridge node: xxx` | 节点名未在 Gateway 创建，先执行 `POST /api/bridge/nodes` |
| `Authentication failed` | Token 错误，确认创建节点时返回的 token |
| 连接后立即断开 | 检查 Gateway 日志（`token mismatch` 或其他错误） |

### 8.2 工具调用没走 Bridge

| 症状 | 排查步骤 |
|------|----------|
| Agent 返回服务器文件 | 1. `curl /api/agent/<name>` 确认 `bridgeNode` 字段已设置 <br> 2. `curl /api/bridge/nodes` 确认节点 `status: CONNECTED` <br> 3. 确认 Agent 的 `toolsEnabled` 包含 readFile/exec 等工具 |
| 部分工具走 Bridge，部分不走 | 正常行为。只有客户端注册的工具才会转发，其余在服务端执行 |

### 8.3 命令执行超时

- 调整工具参数中的 `timeoutSeconds`
- 检查客户端机器负载
- 查看客户端日志中的详细错误

### 8.4 断线重连

- 客户端内置指数退避重连（1s → 2s → ... → 30s）
- 重连成功后自动重新注册
- 断线期间的工具调用会回退到服务端执行
- 如果持续无法重连，检查网络和 Gateway 状态

---

## 9. 扩展：添加新工具

### 9.1 场景分类

| 场景 | 改服务端 | 改客户端 |
|------|----------|----------|
| 已有服务端工具，想在本地执行 | 不需要 | 添加实现 + 注册工具名 |
| 全新的本地专属工具 | 定义 ToolCallback（名称、描述、参数） | 添加实现 + 注册工具名 |
| 服务端专属工具（不需要本地执行） | 添加 ToolCallback | 不需要 |

### 9.2 客户端添加工具步骤

1. **实现工具逻辑**：在 `src/tools/` 下创建文件，导出执行函数
2. **注册到 ToolExecutor**：在 `tool-executor.ts` 的 `dispatch` 方法中添加 case
3. **添加到注册列表**：在 `bridge-client.ts` 的 `register` 消息中加入工具名

客户端注册的工具名必须与服务端 `ToolCallback` 的名称完全一致。Bridge 路由按名称精确匹配。

### 9.3 服务端添加工具步骤

1. **创建 Tool 类**：实现 `ToolCallback` 或使用 `@Tool` 注解
2. **注册为 Bean**：通过 `ToolCallbackProvider` 注入 Spring 容器
3. 工具会自动出现在 `ToolsEngine` 的全局注册表中

如果这个工具也需要在客户端执行，无需额外配置——只要客户端注册了同名工具，Bridge 路由自动生效。

---

## 10. 未来扩展 TODO

- [ ] **按 Session 绑定节点（多用户支持）** — 当前 `bridgeNode` 绑定在 Agent 级别，所有用户共享同一个绑定。未来可扩展为按用户会话（session）动态绑定，每个用户自动关联自己的 Bridge 节点。
- [ ] **前端 UI 管理 Bridge 节点** — 在 Web UI 中增加 Bridge 节点管理页面（创建/删除/查看状态），替代当前的 REST API 操作。
- [ ] **发布到 npm** — 将 `intellimate-local` 发布到 npm，用户可直接 `npx intellimate-local` 使用。
- [ ] **本地 MCP 工具代理** — 客户端代理本地 MCP Server 的工具，使远程 Agent 能调用本地 MCP 工具。
- [ ] **安全增强** — 支持 TLS/WSS 加密、IP 白名单、操作审计日志。

---

## 11. 项目结构参考

### 11.1 服务端新增代码

```
intellimate-agent/src/main/java/.../agent/tools/bridge/
└── BridgeToolProvider.java            # SPI 接口（agent 模块定义）

intellimate-gateway/src/main/java/.../gateway/bridge/
├── BridgeProtocol.java                # WebSocket JSON 消息类型定义
├── BridgeNodeSession.java             # 单个节点的 WebSocket 会话管理
├── BridgeNodeRegistry.java            # 已连接节点的内存注册表
├── BridgeWebSocketHandler.java        # WebSocket 端点处理器
├── BridgeToolCallback.java            # 转发工具调用的 ToolCallback 实现
├── BridgeToolProviderImpl.java        # BridgeToolProvider SPI 实现
└── BridgeController.java              # REST API 控制器

intellimate-gateway/src/main/resources/db/migration/
└── V26__bridge_node.sql               # 数据库迁移
```

### 11.2 客户端代码

```
intellimate-local/
├── package.json                       # npm 包定义
├── tsconfig.json                      # TypeScript 配置
├── config.example.yml                 # 配置文件示例
└── src/
    ├── index.ts                       # CLI 入口，解析参数
    ├── config.ts                      # 配置加载（CLI + YAML）
    ├── bridge-client.ts               # WebSocket 客户端，负责连接/注册/重连
    ├── tool-executor.ts               # 工具分发器，安全校验后路由到具体实现
    └── tools/
        ├── file-ops.ts                # readFile/writeFile/editFile/listFiles 实现
        └── exec.ts                    # exec 命令执行（支持流式输出和超时）
```

### 11.3 服务端修改的已有文件

| 文件 | 修改内容 |
|------|----------|
| `AgentRunRequest.java` | 新增 `bridgeNode` 字段 |
| `ResolvedAgentConfig.java` | 新增 `bridgeNode` 字段 |
| `AgentConfigService.java` | 从 DB 读取 bridgeNode 并传入配置 |
| `AgentEntity.java` | 新增 `bridgeNode` 持久化字段 |
| `ToolsEngine.java` | 新增三参数 `getToolCallbacksFor` 方法（含 Bridge 路由） |
| `AgentRuntime.java` | 调用新的三参数方法；将 toolCallbackMap 贯穿执行链 |
| `MessagePipeline.java` | 构造 AgentRunRequest 时传入 bridgeNode |
| `AgentPromptJob.java` | 同上（定时任务场景） |
| `AgentController.java` | PUT/GET 接口支持 bridgeNode 字段 |
| `WebSocketRouterConfig.java` | 注册 `/api/bridge/connect` 端点 |
