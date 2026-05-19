# Bridge 本地执行节点使用指南

> IntelliMate Bridge 允许服务器部署的 Agent 透明地在用户本地机器上执行文件操作、命令和工具调用。

## 架构概览

```
┌─────────────────────────────────────┐
│  服务器端 (Gateway)                  │
│                                     │
│  Web UI ── Gateway ── AgentRuntime  │
│                          │          │
│              ToolsEngine + Router   │
│                          │          │
│          WebSocket /api/bridge ◄────│──── 本地组件主动连接
│                                     │
└─────────────────────────────────────┘
                    ▲
                    │ WebSocket (JSON)
                    │
┌───────────────────┴─────────────────┐
│  用户本地 (intellimate-local)        │
│                                     │
│  readFile / writeFile / editFile    │
│  exec / listFiles                   │
└─────────────────────────────────────┘
```

**工作原理：**
1. 本地组件通过 WebSocket **主动连接**到服务器 Gateway
2. 连接后注册自身及可用工具列表
3. Agent 调用工具时，Gateway 内部路由层判断是否走 Bridge
4. 如果 Agent 绑定了 Bridge 节点，匹配的工具调用通过 WebSocket 转发到本地
5. 本地执行结果返回给 Agent，Agent 完全透明（工具名不变）

## 快速开始

### 前置条件

- Gateway 已部署并运行（端口默认 3007）
- 本地机器已安装 Node.js >= 18
- 本地机器可以访问 Gateway 的 WebSocket 端口

### 步骤 1：创建 Bridge 节点

通过 Gateway REST API 创建一个 Bridge 节点。该操作会生成一个认证 token。

```bash
curl -X POST http://<gateway-host>:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -d '{"name": "my-laptop"}'
```

**响应示例：**

```json
{
  "id": 1,
  "name": "my-laptop",
  "token": "6db15171512641279b59272d084efade",
  "message": "请保存此 token，它只显示一次。使用方式：npx intellimate-local --server ws://host:3007/api/bridge/connect --token 6db15171512641279b59272d084efade"
}
```

> **重要：** token 只在创建时返回一次，请妥善保存。

### 步骤 2：启动本地组件

```bash
cd intellimate-local
npm install && npm run build

node dist/index.js \
  --server ws://<gateway-host>:3007/api/bridge/connect \
  --token <步骤1返回的token> \
  --name my-laptop
```

**成功连接的输出：**

```
[intellimate-local] Node "my-laptop" connecting to ws://<gateway-host>:3007/api/bridge/connect
[bridge] Connected to gateway
[bridge] Registered as node: 1
```

### 步骤 3：绑定 Agent 到 Bridge 节点

```bash
curl -X PUT http://<gateway-host>:3007/api/agent/<agent-name> \
  -H 'Content-Type: application/json' \
  -d '{"bridgeNode": "my-laptop"}'
```

### 步骤 4：使用

在 Web UI 中选择已绑定 Bridge 的 Agent，正常对话即可。当 Agent 需要读写文件或执行命令时，操作会自动在你的本地机器上执行。

**测试示例：**
- "请读取 /Users/user/project/main.py 的内容"
- "请列出 /Users/user/projects 目录下的文件"
- "请执行 `git status` 命令"
- "请将以下内容写入 /tmp/test.txt: Hello Bridge!"

## 配置文件（可选）

除了命令行参数，本地组件也支持 YAML 配置文件：

```yaml
# config.yml
server: "ws://your-server:3007/api/bridge/connect"
token: "your-token-here"
name: "my-laptop"

security:
  allowedPaths:          # 限制可访问的文件路径（可选）
    - "/Users/user/projects"
    - "/tmp"
  blockedCommands:       # 禁止执行的命令模式（可选）
    - "rm -rf /"
    - "sudo *"
```

使用配置文件启动：

```bash
node dist/index.js --config ./config.yml
```

## REST API 参考

### Bridge 节点管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/bridge/nodes` | 创建节点（返回 token） |
| `GET` | `/api/bridge/nodes` | 列出所有节点及状态 |
| `DELETE` | `/api/bridge/nodes/{name}` | 删除节点 |

### Agent 绑定

| 方法 | 路径 | 说明 |
|------|------|------|
| `PUT` | `/api/agent/{name}` | 更新 Agent 配置 |

更新 Agent 时传入 `{"bridgeNode": "节点名称"}` 进行绑定，传 `{"bridgeNode": null}` 取消绑定。

### 响应示例

**创建节点：**

```bash
curl -X POST http://localhost:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -d '{"name": "office-pc"}'
```

```json
{
  "id": 2,
  "name": "office-pc",
  "token": "a1b2c3d4e5f6...",
  "message": "请保存此 token..."
}
```

**查看节点状态：**

```bash
curl http://localhost:3007/api/bridge/nodes
```

```json
[
  {
    "id": 1,
    "name": "my-laptop",
    "status": "CONNECTED",
    "registeredTools": "[\"readFile\", \"writeFile\", \"editFile\", \"exec\", \"listFiles\"]",
    "lastConnectedAt": "2026-05-19T11:08:57",
    "lastHeartbeatAt": "2026-05-19T11:14:52"
  }
]
```

## WebSocket 通信协议

本地组件与 Gateway 之间通过 WebSocket 进行 JSON 消息通信。

### 连接地址

```
ws://<gateway-host>:<port>/api/bridge/connect?token=<token>
```

### 消息类型

#### 1. 注册（本地 → Gateway）

```json
{
  "type": "register",
  "name": "my-laptop",
  "tools": ["readFile", "writeFile", "editFile", "exec", "listFiles"],
  "mcpTools": []
}
```

#### 2. 注册确认（Gateway → 本地）

```json
{
  "type": "registered",
  "nodeId": "1"
}
```

#### 3. 工具调用（Gateway → 本地）

```json
{
  "type": "tool_call",
  "id": "uuid-xxx",
  "tool": "readFile",
  "args": { "path": "/Users/user/project/main.py" }
}
```

#### 4. 工具结果（本地 → Gateway）

```json
{
  "type": "tool_result",
  "id": "uuid-xxx",
  "success": true,
  "result": "file content..."
}
```

#### 5. 流式输出（本地 → Gateway，仅 exec 命令）

```json
{
  "type": "tool_stream",
  "id": "uuid-xxx",
  "chunk": "Running tests...\n"
}
```

#### 6. 心跳

```json
// Gateway → 本地
{ "type": "ping" }

// 本地 → Gateway
{ "type": "pong" }
```

#### 7. 错误

```json
{
  "type": "tool_result",
  "id": "uuid-xxx",
  "success": false,
  "error": "File not found: /path/to/file"
}
```

## 可用工具

Bridge 支持以下 5 个工具的本地执行：

| 工具 | 参数 | 说明 |
|------|------|------|
| `readFile` | `path`, `startLine?`, `lineCount?` | 读取本地文件内容 |
| `writeFile` | `path`, `content` | 写入文件（自动创建目录） |
| `editFile` | `path`, `oldText`, `newText` | 精确文本替换（要求 oldText 唯一） |
| `listFiles` | `path`, `pattern?`, `recursive?` | 列出目录内容 |
| `exec` | `command`, `workingDirectory?`, `timeoutSeconds?` | 执行 Shell 命令（默认 30s 超时） |

## 安全机制

### Token 认证

- 每个 Bridge 节点有唯一的认证 token
- Token 的 SHA-256 散列存储在数据库中
- WebSocket 连接时通过 query parameter 传递 token

### 路径白名单

配置 `security.allowedPaths` 可限制文件操作的路径范围：

```yaml
security:
  allowedPaths:
    - "/Users/user/projects"
    - "/tmp"
```

未在白名单中的路径操作会被拒绝。

### 命令黑名单

配置 `security.blockedCommands` 可阻止危险命令：

```yaml
security:
  blockedCommands:
    - "rm -rf /"
    - "sudo *"
```

支持通配符匹配（`*` 转换为 `.*` 正则）。

### exec 保护

- 单次命令输出上限 1MB（防止 OOM）
- 默认超时 30 秒（可通过参数调整）
- 超时后进程会被强制终止

## 断线与重连

- 本地组件内置指数退避重连：1s → 2s → 4s → ... → 30s 上限
- 重连成功后自动重新注册
- Agent 调用时如果 Bridge 离线，会返回错误提示，不会中断对话
- 本地组件的日志会显示连接状态变化

## 数据库表结构

### bridge_node 表

```sql
CREATE TABLE bridge_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,      -- 节点名称
    token_hash VARCHAR(128) NOT NULL,      -- Token SHA-256 散列
    status VARCHAR(16) DEFAULT 'DISCONNECTED',
    registered_tools JSON,                  -- 注册的工具列表
    last_connected_at DATETIME,
    last_heartbeat_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### agent 表新增字段

```sql
ALTER TABLE agent ADD COLUMN bridge_node VARCHAR(64) DEFAULT NULL;
```

## 项目构建

### Gateway（服务器端）

```bash
cd /path/to/IntelliMate

# 编译所有模块
mvn compile

# 打包（跳过测试）
mvn package -DskipTests

# 开发模式运行
mvn spring-boot:run -pl intellimate-gateway

# 生产模式运行（使用 jar）
java -jar intellimate-gateway/target/intellimate-gateway-0.0.1-SNAPSHOT.jar
```

**环境变量（生产部署时设置）：**

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `INTELLIMATE_PORT` | 服务端口 | 3007 |
| `MYSQL_HOST` | MySQL 地址 | localhost |
| `MYSQL_PORT` | MySQL 端口 | 3306 |
| `MYSQL_DATABASE` | 数据库名 | intellimate |
| `MYSQL_USERNAME` | 数据库用户 | root |
| `MYSQL_PASSWORD` | 数据库密码 | — |
| `INTELLIMATE_AUTH_TOKEN` | API 认证 token | — |

### 前端 Web UI

```bash
cd intellimate-web

# 安装依赖
npm install

# 开发模式（热重载）
npm run dev     # 启动在 http://localhost:5173

# 生产构建
npm run build   # 输出到 dist/ 目录
```

生产部署时，将 `dist/` 目录部署到 Web 服务器（如 Nginx），配置反向代理到 Gateway。

### 本地组件（用户电脑）

```bash
cd intellimate-local

# 安装依赖
npm install

# 编译 TypeScript
npm run build

# 运行
node dist/index.js \
  --server ws://<gateway-host>:3007/api/bridge/connect \
  --token <token> \
  --name my-laptop
```

**未来发布到 npm 后：**

```bash
npx intellimate-local \
  --server ws://<gateway-host>:3007/api/bridge/connect \
  --token <token> \
  --name my-laptop
```

### 一键构建全部

```bash
cd /path/to/IntelliMate

# 后端
mvn package -DskipTests

# 前端
cd intellimate-web && npm install && npm run build && cd ..

# 本地组件
cd intellimate-local && npm install && npm run build && cd ..
```

## 故障排查

### 本地组件无法连接

1. 检查 Gateway 是否在运行：`curl http://<host>:3007/actuator/health`
2. 检查 token 是否正确
3. 检查节点名称是否已在数据库中创建
4. 检查网络连通性：`curl http://<host>:3007/api/bridge/nodes`

### 工具调用失败

1. 检查节点状态：`curl http://<host>:3007/api/bridge/nodes`
2. 确认节点状态为 CONNECTED
3. 查看本地组件日志中的工具调用记录
4. 检查安全配置（路径白名单、命令黑名单）

### Agent 没有使用 Bridge

1. 确认 Agent 的 `bridgeNode` 已设置：`curl http://<host>:3007/api/agent/<name>`
2. 确认 Bridge 节点在线
3. 确认 Agent 的 `toolsEnabled` 包含需要桥接的工具（如 readFile、exec）

## 项目结构

### Gateway 新增代码

```
intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/
├── BridgeProtocol.java           # WebSocket JSON 消息协议
├── BridgeNodeSession.java        # 单个节点的 WebSocket 会话
├── BridgeNodeRegistry.java       # 已连接节点的内存注册表
├── BridgeWebSocketHandler.java   # /api/bridge/connect WebSocket 端点
├── BridgeToolCallback.java       # 通过 Bridge 转发的 ToolCallback
├── BridgeToolProviderImpl.java   # BridgeToolProvider SPI 实现
└── BridgeController.java         # REST API

intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/bridge/
└── BridgeToolProvider.java       # SPI 接口
```

### 本地组件

```
intellimate-local/
├── package.json
├── tsconfig.json
├── config.example.yml
└── src/
    ├── index.ts              # CLI 入口
    ├── bridge-client.ts      # WebSocket 客户端
    ├── tool-executor.ts      # 工具分发与安全校验
    ├── config.ts             # 配置加载
    └── tools/
        ├── file-ops.ts       # 文件操作工具
        └── exec.ts           # 命令执行工具
```
