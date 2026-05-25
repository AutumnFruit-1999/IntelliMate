# Bridge 本地执行系统

## 概述

Bridge 系统让 Agent 能够在用户的本地机器上执行工具，而不是在 Gateway 服务器上运行。这对于需要访问用户本地文件系统、运行本地命令、操作本地开发环境的场景至关重要。

系统由三部分组成：Gateway 端的代理层负责接收 Agent 的工具调用并转发；Node.js 客户端（intellimate-local）运行在用户机器上执行实际操作；两者之间通过专用的 WebSocket 连接和 RPC 协议通信。

## 工作原理

整体流程分为五个阶段：

### 节点注册

管理员通过 REST API 创建一个 Bridge 节点，系统生成一次性令牌。管理员将令牌和连接命令提供给用户。

### 客户端连接

用户在本地运行 intellimate-local 客户端，使用令牌连接到 Gateway 的 Bridge WebSocket 端点。连接建立后客户端发送 register 消息，报告节点名称和可用工具列表。

### Agent 配置绑定

通过 Agent 管理 API 将某个 Agent 的 bridge_node 字段设置为已注册节点的名称，表示该 Agent 的工具调用应该路由到该节点执行。

### 工具路由

Agent 执行时，ToolsEngine 检查 Agent 是否绑定了 Bridge 节点。如果是，将节点注册的工具名称与 Agent 可用工具列表做匹配，匹配到的工具用 BridgeToolCallback 替换原来的本地回调。LLM 看到的工具定义不变，只有执行位置发生变化。

### 远程执行

Agent 调用被路由的工具时，BridgeToolCallback 将调用参数序列化为 JSON，通过 WebSocket 发送 tool_call 消息到客户端。客户端执行后返回 tool_result，Gateway 将结果传回 Agent。

## 通信协议

Bridge 使用自定义的 JSON RPC 协议，通过 type 字段区分消息类型。

### register（客户端 → 服务端）

客户端连接后发送，包含节点名称和可用工具列表。

```json
{
  "type": "register",
  "name": "my-laptop",
  "tools": ["readFile", "writeFile", "editFile", "exec", "listFiles"],
  "mcpTools": []
}
```

### registered（服务端 → 客户端）

注册确认，返回数据库中的节点 ID。

```json
{
  "type": "registered",
  "nodeId": 42
}
```

### tool_call（服务端 → 客户端）

Agent 发起工具调用时发送。

```json
{
  "type": "tool_call",
  "id": "uuid-request-id",
  "tool": "readFile",
  "args": { "path": "/home/user/project/src/main.js", "startLine": 1, "endLine": 50 }
}
```

### tool_result（客户端 → 服务端）

工具执行完成后返回结果。

```json
{
  "type": "tool_result",
  "id": "uuid-request-id",
  "success": true,
  "result": "file content here...",
  "error": null,
  "exitCode": null
}
```

### ping / pong

心跳保活，服务端每 30 秒发送 ping，客户端回复 pong。服务端更新数据库中的 last_heartbeat_at 字段。

### error（服务端 → 客户端）

认证失败或注册错误时发送。

## Gateway 端组件

### BridgeController

REST API 端点（/api/bridge/*），管理 Bridge 节点：

- POST /api/bridge/nodes：创建节点，生成 UUID 令牌，存储令牌的 SHA-256 哈希，返回连接命令
- GET /api/bridge/nodes：列出所有节点及状态
- DELETE /api/bridge/nodes/{name}：删除节点并注销活跃连接

### BridgeWebSocketHandler

WebSocket 端点 /api/bridge/connect，处理 Bridge 客户端连接：

认证流程：从 URL 参数获取 token → 计算 SHA-256 → 与 bridge_node 表中的 token_hash 比对。

连接管理：每个节点名称同一时间只允许一个活跃连接。如果同名节点重新连接，旧连接被关闭替换。

消息分发：接收客户端的 JSON 消息，根据 type 分发到对应处理逻辑（register → 注册、tool_result → 转发结果、pong → 更新心跳）。

### BridgeNodeRegistry

内存中的节点会话映射表（ConcurrentHashMap），以节点名称为键：

- register(name, session)：注册新会话
- unregister(name)：移除会话
- getSession(name)：获取活跃会话
- isConnected(name)：检查连接状态
- getRegisteredTools(name)：获取节点注册的工具列表

### BridgeNodeSession

封装单个 Bridge 连接的状态和通信能力：

- 维护 WebSocket 输出流（Sinks.Many）
- callTool(toolName, argsJson)：发送 tool_call 并等待 tool_result
- 使用 CompletableFuture 实现请求-响应匹配，通过 UUID 关联
- 超时设置为 120 秒
- 连接断开时所有待处理的 Future 异常完成

### BridgeToolProviderImpl

实现 Agent 模块定义的 BridgeToolProvider 接口，桥接 Agent 侧和 Gateway 侧：

- isConnected(nodeName)：委托给 BridgeNodeRegistry
- getRegisteredTools(nodeName)：委托给 BridgeNodeRegistry
- createBridgeCallback(nodeName, toolDefinition)：创建 BridgeToolCallback 实例

### BridgeToolCallback

Spring AI ToolCallback 的实现，将工具调用转发到远程节点：

1. 接收 Agent 的工具调用参数
2. 通过 BridgeNodeSession.callTool 发送到客户端
3. 等待结果返回
4. 如果执行失败，结果以 "Error (bridge): " 前缀标识

## Agent 端接口

### BridgeToolProvider

定义在 intellimate-agent 模块中的接口，ToolsEngine 通过此接口判断和路由 Bridge 工具：

- isConnected(nodeName)：节点是否在线
- getRegisteredTools(nodeName)：节点注册了哪些工具
- createBridgeCallback(nodeName, toolDef)：创建远程执行回调

### ToolsEngine 路由逻辑

getToolCallbacksFor 方法中，如果 Agent 绑定了 bridgeNode 且节点在线：

1. 获取节点注册的工具名称集合
2. 遍历 Agent 的所有工具回调
3. 如果工具名称在节点注册列表中，用 BridgeToolCallback 替换原始回调
4. 未匹配的工具保持在服务端本地执行

这种按名称精确匹配的机制确保只有节点声明可执行的工具才会被路由。

## intellimate-local 客户端

Node.js 编写的命令行客户端，运行在用户的本地机器上。

### 启动方式

```
npx intellimate-local --server ws://gateway-host/api/bridge/connect --token <token> --name <node-name>
```

也支持通过 YAML 配置文件指定连接参数和安全策略。

### 注册的本地工具

| 工具 | 功能 |
|------|------|
| readFile | 读取本地文件，支持行范围 |
| writeFile | 写入本地文件 |
| editFile | 搜索替换编辑文件 |
| exec | 执行 Shell 命令（通过 sh -c） |
| listFiles | 列出目录内容 |

### 安全配置

通过 config.yml 或命令行参数配置：

- allowedPaths：允许文件操作的路径白名单，所有文件工具（readFile / writeFile / editFile / listFiles）仅限在白名单路径下操作
- blockedCommands：命令黑名单，exec 工具会拒绝匹配的命令

### 连接管理

客户端内置自动重连机制，使用指数退避策略（1 秒起步，最大 30 秒），确保网络中断后自动恢复连接。

## 数据库

### bridge_node 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| name | VARCHAR(64) | 节点名称（唯一约束） |
| token_hash | VARCHAR(128) | 令牌的 SHA-256 哈希 |
| status | VARCHAR(16) | 连接状态（CONNECTED / DISCONNECTED） |
| registered_tools | JSON | 节点注册的工具列表 |
| last_connected_at | DATETIME | 最后连接时间 |
| last_heartbeat_at | DATETIME | 最后心跳时间 |
| created_at / updated_at | DATETIME | 时间戳 |

### agent 表扩展

agent 表增加了 bridge_node 字段（VARCHAR(64)），存储该 Agent 绑定的 Bridge 节点名称。在 Agent 配置解析时加载，随 AgentRunRequest 传递到 ToolsEngine。

## Agent 绑定

通过 Agent REST API 设置 Agent 与 Bridge 节点的绑定：

```
PUT /api/agents/{id}
{ "bridgeNode": "my-laptop" }
```

绑定后，该 Agent 的所有运行场景（主聊天、心跳引擎、定时任务、委派）都会使用 Bridge 路由。

## REST API

| 端点 | 说明 |
|------|------|
| POST /api/bridge/nodes | 创建节点，返回令牌和连接命令 |
| GET /api/bridge/nodes | 列出所有节点 |
| DELETE /api/bridge/nodes/{name} | 删除节点 |

## 设计要点

透明替换：Bridge 路由对 LLM 完全透明。工具的名称、描述、参数定义不变，LLM 无需知道工具在哪里执行。只要节点注册了同名工具，执行就自动路由到本地。

令牌安全：令牌仅在创建时显示一次，数据库只存储哈希值。即使数据库泄露，也无法恢复原始令牌。

单连接策略：每个节点名称只允许一个活跃 WebSocket 连接，重连自动替换旧连接，避免消息路由混乱。

同步 RPC：工具调用使用 CompletableFuture 实现同步等待模式，120 秒超时。这简化了请求-响应的关联逻辑，但在响应式管道中需要切换到弹性线程池执行。

按需路由：只有 Agent 明确绑定了 Bridge 节点、节点在线、且工具名称匹配的情况下才会路由。不满足任一条件时，工具在服务端本地执行，保证降级可用。

路径安全：本地客户端的路径白名单和命令黑名单提供了第一道防线，防止 Agent 访问敏感文件或执行危险命令。
