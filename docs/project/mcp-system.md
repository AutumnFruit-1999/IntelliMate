# IntelliMate MCP 系统

## 什么是 MCP

MCP（Model Context Protocol，模型上下文协议）是一套标准化协议，让 AI Agent 能够连接和使用外部工具服务。在 IntelliMate 中，MCP 系统允许用户将第三方工具服务（比如搜索引擎、数据库查询、文件系统操作等）接入到 Agent 的能力范围中，而不需要编写任何代码。

简单来说，内置工具是硬编码在系统中的固定能力（如读写文件、执行命令），而 MCP 工具是通过标准协议从外部服务器动态发现的扩展能力。用户只需要配置一个 MCP 服务器的地址，系统就能自动发现该服务器提供的所有工具，并让 Agent 在对话中使用它们。

---

## 系统架构

MCP 系统横跨三个模块，各司其职。

intellimate-agent 模块定义了 MCP 工具提供者的 SPI 接口（McpToolProvider）和工具名称前缀包装器（PrefixedToolCallback），以及工具注册引擎（ToolsEngine）中 MCP 工具的合并和过滤逻辑。这个模块不关心 MCP 的具体连接和通信细节，只知道"有一些外部工具可以调用"。

intellimate-gateway 模块是 MCP 的核心实现层。包含 MCP 客户端连接管理（McpToolProviderImpl）、数据库实体和存储（McpServerEntity / McpServerRepository）、以及 REST API 控制器（McpServerController）。所有与外部 MCP 服务器的通信都在这个模块中完成。

intellimate-web 前端模块提供 MCP 服务器的管理界面和每个 Agent 的 MCP 工具选择界面。

---

## MCP 服务器管理

### 服务器配置

每个 MCP 服务器在数据库的 mcp_server 表中保存为一条记录，包含以下关键信息：

**name**：服务器的唯一标识名称，只允许字母、数字、下划线和连字符，以字母开头，最长 64 个字符。这个名称会被用作工具名的前缀，所以需要简洁有意义，比如 "jina"、"github"、"postgres"。

**serverUrl**：服务器地址。对于 SSE 和 Streamable HTTP 传输类型，这是一个标准的 URL（如 https://mcp.example.com/sse）。对于 STDIO 传输类型，这是一个 JSON 格式的命令配置（如 {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"]}）。

**transportType**：传输类型，决定了系统如何与 MCP 服务器通信。支持三种类型：

- STREAMABLE_HTTP：通过 HTTP 长连接进行双向通信，是推荐的传输方式。客户端向服务器发送 HTTP 请求，服务器通过 SSE 事件流返回响应。这种方式兼容性最好，不需要维护长连接。

- SSE：通过 Server-Sent Events 建立持久连接。客户端通过 HTTP 建立一个长连接接收服务器的事件推送，同时通过另一个 HTTP 端点发送请求。这是较早期的 MCP 传输方式。

- STDIO：通过标准输入输出与本地进程通信。系统启动一个本地子进程，通过 stdin/stdout 管道进行数据交换。适用于运行在本地的 MCP 服务器，比如 Node.js 实现的文件系统工具。

**authConfig**：认证配置，JSON 格式的 HTTP 头部键值对。用于向需要认证的 MCP 服务器发送凭证，比如 API Key。仅适用于 SSE 和 Streamable HTTP 类型。

**enabled**：启用状态，1 表示启用，0 表示禁用。禁用的服务器不会提供工具给任何 Agent。

**last_connected_at**：最后一次成功连接的时间。

**tools_discovered**：缓存的已发现工具名称列表，JSON 数组格式。用于在不重新连接的情况下展示服务器提供的工具。

MCP 服务器是全局共享的——所有 Agent 共享同一个 MCP 服务器池。具体哪个 Agent 能使用哪些 MCP 工具，通过 Agent 表上的 mcp_tools_enabled 字段独立控制。这个设计在 V11 迁移中确立，之前的版本支持将 MCP 服务器绑定到特定 Agent，但这种方式被证明不够灵活。

### 服务器生命周期

**启动时自动连接**：应用启动后，McpToolProviderImpl 监听 Spring 的 ApplicationReadyEvent 事件，从数据库加载所有 MCP 服务器记录，逐个尝试连接。连接失败的服务器会被自动设置为禁用状态（enabled=0），避免影响系统启动。连接成功后调用 ToolsEngine.refresh() 将发现的工具注册到全局工具表中。

**创建服务器**：通过 REST API 创建新的 MCP 服务器记录后，系统立即尝试连接并发现工具。如果连接成功，将发现的工具名称缓存到 tools_discovered 字段，并刷新全局工具表。

**更新服务器**：修改服务器配置时，系统先断开旧连接，保存新配置到数据库，如果服务器处于启用状态则用新配置重新连接，最后刷新工具表。

**删除服务器**：从数据库删除记录，断开连接，刷新工具表。

**启用/禁用**：禁用时断开连接并刷新工具表（该服务器的工具从全局工具表中移除）。启用时重新连接并刷新。

**手动重连**：通过 REST API 可以触发重连所有已启用的服务器，返回连接成功数、失败数和总工具数。

系统目前没有后台健康检查机制——不会定期检测 MCP 服务器是否仍然可用。连接状态只在启动、CRUD 操作或手动重连时更新。

---

## 工具发现机制

当系统连接到一个 MCP 服务器时，工具发现过程如下：

首先，系统根据服务器的传输类型创建对应的 MCP 客户端（McpSyncClient），然后调用 initialize 方法完成 MCP 协议握手。握手成功后，系统通过 Spring AI 提供的 SyncMcpToolCallbackProvider 包装客户端，该包装器内部调用 MCP 协议的 tools/list 方法，获取服务器上所有可用工具的定义（包括工具名称、描述和参数模式）。

获取到的工具列表经过名称前缀处理后存入内存缓存。前缀格式为 mcp_{服务器名称}_{原始工具名称}，服务器名称中的非字母数字字符会被替换为下划线。例如，名为 "jina" 的服务器提供了一个叫 "search_web" 的工具，在 IntelliMate 中就变成了 "mcp_jina_search_web"。

这种前缀机制解决了两个问题：第一，避免不同 MCP 服务器提供同名工具时发生冲突（比如两个服务器都有 "search" 工具）；第二，让系统能够根据工具名快速识别它来自哪个 MCP 服务器。

发现的工具名称会被缓存到数据库的 tools_discovered 字段中。这个缓存供前端管理界面使用，让用户在不重新连接服务器的情况下也能看到该服务器提供了哪些工具。

---

## 工具执行流程

MCP 工具的执行与内置工具完全一致，对 Agent 来说是透明的。

当 Agent 在对话中决定调用一个 MCP 工具时（比如 mcp_jina_search_web），AgentRuntime 从已过滤的工具回调表中找到对应的 PrefixedToolCallback。PrefixedToolCallback 去掉前缀后，将调用委托给底层的 Spring AI MCP 回调，该回调通过 McpSyncClient 向 MCP 服务器发送 tools/call 请求。

MCP 服务器执行实际操作后返回结果，结果经由相同的路径返回到 Agent。Agent 将工具结果作为 ToolResponseMessage 添加到对话历史中，然后进入下一轮推理。

MCP 工具执行同样经过系统的中间件链处理，包括循环检测（防止 Agent 反复调用同一工具）、审批门控（配置了需要人工审批的工具会暂停等待确认）、结果缓存和超时控制。这些保护机制对所有工具类型统一适用。

---

## 每 Agent 的 MCP 工具配置

虽然 MCP 服务器是全局共享的，但每个 Agent 可以独立控制自己使用哪些 MCP 工具。这通过 agent 表的 mcp_tools_enabled 字段实现。

该字段与 tools_enabled（内置工具控制）完全独立。两个字段可以自由组合——一个 Agent 可以有全部内置工具但没有 MCP 工具，也可以没有内置工具但有特定的 MCP 工具。

mcp_tools_enabled 支持三种配置：

- 留空或不设置：该 Agent 不使用任何 MCP 工具。这是默认值。
- 设为 "full"：该 Agent 可以使用所有已连接 MCP 服务器的全部工具。
- 设为 JSON 数组：精确指定允许使用的 MCP 工具名称列表，例如 ["mcp_jina_search_web", "mcp_github_list_repos"]。

工具过滤发生在每次对话请求时。AgentConfigService 从数据库加载 Agent 配置，将 mcp_tools_enabled 的值传递给 AgentRuntime，再由 ToolsEngine 根据这个值过滤 MCP 工具列表。只有通过过滤的 MCP 工具才会被注册到 LLM 的可调用工具列表中。

对于 Agent 委托（delegateAgent）场景，被委托的子 Agent 使用自己的 mcp_tools_enabled 配置，不继承父 Agent 的设置。

---

## 协议实现细节

### SDK 版本

系统使用 MCP Java SDK 0.11.3 版本和 Spring AI MCP Client WebFlux 1.0.0 版本。Spring Boot 内置的 MCP 自动配置被显式禁用（application.yml 中设置 spring.ai.mcp.client.enabled: false），所有客户端连接由 McpToolProviderImpl 手动管理。

### 传输层实现

SSE 传输使用 Spring WebFlux 的 WebClient 建立连接。WebClient 在创建时会注入 authConfig 中配置的 HTTP 头部作为默认请求头。WebFluxSseClientTransport 管理 SSE 连接的建立和重连。

Streamable HTTP 传输使用 JDK 的 HttpClient。系统从 serverUrl 中提取路径部分作为 MCP 端点路径，剩余部分作为基础 URL。同样支持通过 authConfig 注入认证头。

STDIO 传输使用 ServerParameters 配置本地进程的启动命令、参数和环境变量。StdioClientTransport 管理进程的生命周期和 stdin/stdout 通信。

### 客户端行为

所有传输类型最终都被包装为 McpSyncClient（同步客户端），请求超时为 30 秒。客户端在 initialize 方法中完成 MCP 协议版本协商。系统不配置特定的 MCP 协议版本，由 SDK 和服务器协商决定。

---

## REST API

### MCP 服务器管理

所有接口前缀为 /api/mcp-servers。

**基础操作：**

- GET /api/mcp-servers：获取所有 MCP 服务器列表，包含连接状态和已发现工具信息。
- GET /api/mcp-servers/{id}：获取指定服务器详情。
- POST /api/mcp-servers：创建新服务器。请求体包含 name（名称，必填）、serverUrl（地址，必填）、transportType（传输类型，默认 SSE）和 authConfig（认证配置，可选）。创建后自动尝试连接。
- PUT /api/mcp-servers/{id}：更新服务器配置。支持修改 name、serverUrl、transportType、authConfig 和 enabled 字段。更新后如果是启用状态会自动重连。
- DELETE /api/mcp-servers/{id}：删除服务器，同时断开连接。

**连接控制：**

- POST /api/mcp-servers/reconnect：重连所有已启用的服务器。返回连接成功数、失败数和发现的工具总数。适用于服务器地址变更或网络恢复后的批量重连。
- POST /api/mcp-servers/{id}/test：测试已保存的服务器配置能否成功连接。创建临时客户端进行连接和工具发现测试，测试完成后关闭连接，不影响现有连接状态。
- POST /api/mcp-servers/test-config：测试尚未保存的配置。用于编辑器中的"测试连接"按钮，用户在保存之前验证配置是否有效。

### Agent MCP 配置

- GET /api/agent/{name}：获取 Agent 配置，返回结果中包含 mcpToolsEnabled 字段。
- PUT /api/agent/{name}：更新 Agent 配置，请求体中可以包含 mcpToolsEnabled 字段。

---

## 前端管理界面

### MCP 服务器管理面板

在侧边栏的工具管理入口中，有"MCP 服务"选项卡。面板以列表形式展示所有已配置的 MCP 服务器，每个服务器显示名称、传输类型、连接状态（用 WiFi 图标表示）和已发现的工具数量。

列表支持启用/禁用切换、编辑、删除和测试连接操作。顶部有"添加 MCP 服务器"按钮。

### MCP 服务器编辑器

编辑器根据选择的传输类型动态显示不同的配置字段。Streamable HTTP 和 SSE 类型显示 URL 输入框和 HTTP 头部编辑器（每行一个 Key: Value 格式）。STDIO 类型显示命令、参数列表和环境变量编辑器。

编辑器内置"测试连接"按钮，调用 /test-config 接口在保存前验证配置。编辑器界面标注了推荐使用 Streamable HTTP 类型。

### Agent MCP 工具选择

在 Agent 配置弹窗中，"MCP 工具"选项卡允许为该 Agent 选择可用的 MCP 工具。提供三种模式：不使用 MCP 工具、使用全部 MCP 工具、自定义选择特定工具。自定义模式下展示所有已连接服务器的工具列表，支持勾选。

选项卡顶部有"重连所有服务器"按钮，方便在发现工具列表不完整时手动刷新。

---

## 数据库表结构

### mcp_server 表

由 V5 迁移创建。存储 MCP 服务器的所有配置信息。name 列有唯一索引。V11 迁移删除了 agent_name 列，将服务器从"绑定到特定 Agent"改为"全局共享"。

### agent.mcp_tools_enabled 列

由 V6 迁移添加到 agent 表。TEXT 类型，存储 null、"full"或 JSON 数组格式的 MCP 工具过滤配置。

---

## 与 ToolsEngine 的集成

ToolsEngine 是全局工具注册引擎，负责将内置工具、自定义 HTTP 工具和 MCP 工具合并为统一的工具表。

在 refresh 方法被调用时（发生在 MCP 服务器创建/更新/删除/重连以及应用启动时），ToolsEngine 从三个来源收集工具回调：Spring @Tool 注解的内置方法、tool_definition 数据库表中的自定义 HTTP 工具、以及 McpToolProvider.getAllCallbacks() 返回的 MCP 工具。三者合并后形成全局可用工具的完整列表。

在每次对话请求时，ToolsEngine.getToolCallbacksFor 方法根据 Agent 的 tools_enabled 和 mcp_tools_enabled 配置分别过滤内置工具和 MCP 工具，然后将两个过滤结果合并为该次对话可用的最终工具列表。这个列表被传递给 LLM，决定了 Agent 在本次对话中能调用哪些工具。

MCP 工具在 ToolsEngine 的元数据接口（/api/tools）中被刻意排除。MCP 工具的管理和展示通过独立的 /api/mcp-servers 接口和专用的前端组件完成。

---

## 完整数据流

一条 MCP 工具调用从用户消息到执行完成的完整路径：

1. 用户在前端发送消息。
2. MessagePipeline 接收消息，调用 AgentConfigService.resolve 加载该 Agent 的完整配置，包括 mcp_tools_enabled 字段。
3. 配置被封装进 AgentRunRequest，传递给 AgentRuntime。
4. AgentRuntime 调用 ToolsEngine.getToolCallbacksFor，传入 tools_enabled 和 mcp_tools_enabled。ToolsEngine 返回合并和过滤后的工具回调列表。
5. AgentRuntime 用过滤后的工具列表构建 LLM 的对话选项（ChatOptions），工具定义作为 function definitions 发送给 LLM。
6. LLM 分析用户消息和可用工具后，决定调用某个 MCP 工具（如 mcp_jina_search_web），返回 tool_call 指令。
7. AgentRuntime 的 processToolCalls 方法从工具回调表中查找 mcp_jina_search_web 对应的 PrefixedToolCallback。
8. PrefixedToolCallback 去掉前缀，将调用委托给底层的 Spring AI MCP 回调。
9. MCP 回调通过 McpSyncClient 向 MCP 服务器发送 tools/call 请求。
10. MCP 服务器执行操作，返回结果。
11. 结果经由 PrefixedToolCallback 返回到 AgentRuntime。
12. AgentRuntime 将结果作为 ToolResponseMessage 追加到对话历史，然后将更新后的历史发送给 LLM 进行下一轮推理。

---

## 设计要点

### 全局共享、按需启用

MCP 服务器是全局资源，只需配置一次就可以被任何 Agent 使用。但每个 Agent 通过 mcp_tools_enabled 独立控制自己的 MCP 工具访问范围。这种设计避免了为每个 Agent 重复配置相同的 MCP 服务器，同时保持了细粒度的权限控制。

### 对 Agent 透明

从 Agent（LLM）的角度看，MCP 工具和内置工具没有任何区别。它们以相同的方式出现在工具列表中，以相同的方式被调用，返回结果的格式也一致。所有的协议转换和网络通信细节都被 PrefixedToolCallback 和 Spring AI 的 MCP 客户端封装在底层。

### 手动生命周期管理

系统选择了手动管理 MCP 客户端连接，而不是依赖 Spring Boot 的自动配置。这提供了更大的灵活性——可以在运行时动态添加、删除和重连 MCP 服务器，而不需要重启应用。代价是没有自动的健康检查和重连机制。

### 前缀隔离

通过在工具名前加上 mcp_{serverName}_ 前缀，系统保证了不同 MCP 服务器的工具名不会冲突，同时让人类和系统都能快速识别工具的来源。前缀在 Agent 看来就是工具名的一部分，不需要特殊处理。
