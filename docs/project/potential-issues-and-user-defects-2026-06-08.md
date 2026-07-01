# IntelliMate 潜在问题与用户使用缺陷审查

审查日期：2026-06-08

审查范围：

- 后端配置、认证、工具执行、WebSocket、测试状态
- 前端登录、聊天、Agent 配置、工具、渠道、记忆、监控等关键使用路径
- 本次仅做静态审查和构建验证，未修改业务代码

验证结果：

- `mvn test` 失败，失败集中在 `HeartbeatEngineTest`
- `npm run build` in `intellimate-web` 通过，但 Vite 报大 chunk 警告
- `npm run build` in `intellimate-local` 通过

## 一、必须优先处理的问题

### 1. 默认密钥和密码进入仓库

证据：

- `intellimate-gateway/src/main/resources/application.yml:14`
- `intellimate-gateway/src/main/resources/application.yml:24`
- `intellimate-gateway/src/main/resources/application.yml:31`
- `docker-compose.yml:8`

问题：

配置中包含默认 MySQL root 密码和 DashScope API Key 形式的默认值。如果该 Key 是真实可用的，需要立即轮换。生产环境继续使用这些默认值会导致数据库和模型调用凭证泄露风险。

影响：

- 未授权访问数据库
- 模型 API 被盗刷
- 项目被公开或共享后难以确认密钥是否已扩散

建议：

- 删除配置文件中的真实默认密钥和默认数据库密码
- 改为必须由环境变量注入
- 对已提交过的真实密钥执行轮换
- 为本地开发提供 `.env.example`，只保留占位符

### 2. 未配置认证 token 时 API 和 WebSocket 直接放行

证据：

- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/security/ApiAuthFilter.java:35`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/security/SecurityService.java:38`

问题：

当 `INTELLIMATE_AUTH_TOKEN` 为空时，REST API 和 WebSocket 都会进入开发模式并接受所有连接。这个项目内置文件读写、命令执行、自定义 HTTP 工具、MCP 工具和本地桥接能力，一旦服务暴露到公网，攻击面很大。

影响：

- 未登录用户可访问管理接口
- 未授权用户可发起 Agent 对话
- 在工具启用情况下可能间接触发宿主机命令执行或文件读写

建议：

- 生产环境启动时强制要求 `INTELLIMATE_AUTH_TOKEN` 或 JWT secret
- 开发模式只允许绑定 `localhost`
- 对 `/api`、`/ws`、`/api/bridge/connect` 使用一致的认证策略
- 启动日志中明确标记当前安全模式

### 3. 用户密码存储和登录防护偏弱

证据：

- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/AuthController.java:44`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/AuthController.java:116`

问题：

注册密码最短只要求 4 位，密码哈希使用裸 SHA-256，没有 salt，也没有 bcrypt、argon2 或 PBKDF2。登录接口未见限流、验证码或锁定策略。

影响：

- 数据库泄露后密码容易被离线撞库
- 弱密码注册成本低
- 登录接口容易被暴力尝试

建议：

- 使用 bcrypt、argon2id 或 PBKDF2
- 提高密码复杂度或至少提高最小长度
- 增加登录失败限流和账号保护
- 对历史 SHA-256 密码做渐进式迁移

### 4. Agent 工具缺少服务端沙箱边界

证据：

- `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ExecTool.java:27`
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/FileReadTool.java:27`
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/FileWriteTool.java:20`

问题：

`exec` 直接通过 `sh -c` 执行命令，文件工具可读写任意路径。当前安全性主要依赖 Agent 配置和用户不触发高危工具，但没有服务端层面的路径白名单、命令白名单、运行用户隔离或容器沙箱。

影响：

- Prompt injection 可诱导 Agent 执行高危命令
- 工具误用可能破坏宿主机文件
- 多用户场景下存在越权访问风险

建议：

- 默认禁用 `exec` 和任意路径写入
- 增加服务端路径 allowlist
- 增加命令 allowlist/denylist
- 高危工具默认需要用户审批
- 用独立低权限用户或容器执行工具

## 二、当前已验证的质量问题

### 5. 后端测试失败

验证命令：

```bash
mvn test
```

结果：

`intellimate-gateway` 模块失败 5 个测试，均在 `HeartbeatEngineTest`。

失败原因：

`HeartbeatEngine` 当前调用：

- `chatInjectionService.isAgentReachable(agentName)`

但测试仍然 stub：

- `chatInjectionService.isAgentOnline(agentName)`

Mockito 未 stub 的方法返回 `null`，随后 `.flatMap(...)` 触发 NPE。

证据：

- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java:75`
- `intellimate-gateway/src/test/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngineTest.java:108`

建议：

- 更新测试 stub 为 `isAgentReachable(...)`
- 增加一个测试覆盖 Agent 不在线时不触发心跳
- 保持代码修改和测试同步，避免启动或 CI 阶段失败

## 三、用户使用上的主要缺陷

### 1. 断线时提示和实际行为矛盾

证据：

- `intellimate-web/src/components/ComposeArea.tsx:110`
- `intellimate-web/src/components/ComposeArea.tsx:20`
- `intellimate-web/src/hooks/useWebSocket.ts:468`

问题：

输入框在断线时提示“连接已断开，输入消息将在重连后发送...”，但发送按钮实际被禁用，`handleSubmit` 也会因为 `disabled` 直接返回。用户会以为可以离线输入并等待重连发送，但实际不能发送。

用户影响：

- 用户断线时不知道消息是否会被保留
- 重连后需要重新输入
- 对长消息用户尤其不友好

建议：

- 要么支持离线草稿/重连后发送
- 要么修改提示为“连接已断开，重连后才能发送”
- 明确显示重连倒计时和失败状态

### 2. 登录和注册成功后整页刷新

证据：

- `intellimate-web/src/components/LoginPage.tsx:73`

问题：

登录或注册成功后直接 `window.location.reload()`。这会中断 SPA 状态，也让用户感到页面闪断。更严重的是，如果后续引入未保存草稿、跳转来源或登录后回跳路径，这种刷新会丢失上下文。

用户影响：

- 登录体验割裂
- 难以保留进入登录前的目标页面
- 后续扩展 SSO 或邀请链接时成本更高

建议：

- 登录成功后由 auth store 更新状态并路由跳转
- 支持 redirect 参数或保留上次访问路径
- 注册成功后可直接进入主界面或引导配置第一个 Agent

### 3. 工具审批后端已实现，但前端没有交互入口

证据：

- `docs/project/tool-system.md:169`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/AgentEventMapper.java:100`
- `intellimate-web/src/hooks/useWebSocket.ts:55`

问题：

后端会发送 `agent.approval_required`，但前端 `useWebSocket` 没有处理该事件，也没有审批弹窗。用户配置了需要审批的工具后，Agent 可能进入等待审批状态，但用户界面没有明确操作入口。

用户影响：

- 对话看起来“卡住”
- 用户不知道需要批准、拒绝或修改工具参数
- 高危工具审批功能无法真正落地

建议：

- 在前端处理 `agent.approval_required`
- 显示工具名、参数、风险提示
- 提供批准、拒绝、修改参数后批准
- 给等待审批状态增加超时和取消

### 4. Agent 配置弹窗信息密度过高，保存模型不一致

证据：

- `intellimate-web/src/components/AgentConfigModal.tsx:50`
- `intellimate-web/src/components/AgentConfigModal.tsx:121`
- `intellimate-web/src/components/AgentConfigModal.tsx:156`

问题：

一个弹窗内包含 SOUL、AGENTS、工具、MCP、Skills、模型、委派、心跳、任务等 9 个 Tab。部分 Tab 依赖底部保存按钮，部分 Tab 自己保存，部分 Tab 不显示保存按钮。用户很难判断哪些改动已保存，哪些还只是草稿。

用户影响：

- 配置认知成本高
- 容易误以为所有 Tab 都使用同一种保存逻辑
- 切换 Tab 时可能忽略未保存内容

建议：

- 将 Agent 配置拆成页面级配置，而不是超大弹窗
- 每个 Tab 显示独立保存状态
- 对即时保存和手动保存做清晰区分
- 离开 Tab 前提示未保存变更

### 5. Skills 保存存在部分成功风险

证据：

- `intellimate-web/src/components/AgentConfigModal.tsx:126`
- `intellimate-web/src/stores/agentStore.ts:210`
- `intellimate-web/src/stores/agentStore.ts:227`

问题：

保存 Skills 时连续调用 `saveSkillsEnabled()` 和 `saveSkillGroupsEnabled()`。这两个保存请求不是一个事务。第一个成功后会把 `dirty` 设为 false，第二个失败时用户可能看到部分状态已保存、部分未保存。

用户影响：

- Agent 实际可用技能和界面预期不一致
- 用户难以判断失败发生在哪一部分

建议：

- 后端提供一个原子更新接口
- 前端将两个字段作为一次保存操作
- 保存失败时明确列出失败字段

### 6. 模型选择失败时没有错误反馈

证据：

- `intellimate-web/src/components/ModelSelector.tsx:27`
- `intellimate-web/src/components/ModelSelector.tsx:57`

问题：

模型列表请求失败后被静默吞掉，最终只显示一个包含当前值的 select。用户无法知道是没有模型、接口失败、认证失败，还是模型供应商未配置。

用户影响：

- 新建 Agent 时不知道为什么没有可选模型
- 模型配置问题难以自助排查

建议：

- 显示加载失败状态和重试按钮
- 区分“暂无模型”和“请求失败”
- 提供跳转到模型管理页面的入口

### 7. 删除 Agent 只有浏览器 confirm，且没有后果说明

证据：

- `intellimate-web/src/components/Sidebar.tsx:150`

问题：

删除 Agent 使用原生 `window.confirm`，只提示名称，没有说明会影响配置、会话、记忆、任务、委派关系等内容。

用户影响：

- 用户无法判断删除后果
- 误删成本高
- 删除失败也缺少明确错误提示

建议：

- 使用自定义确认弹窗
- 列出会被影响的数据范围
- 要求输入 Agent 名称确认
- 删除后显示 toast 或状态反馈

### 8. 渠道绑定流程说明过于简化

证据：

- `intellimate-web/src/components/ChannelsPage.tsx:161`

问题：

页面只写“生成绑定码 -> 在钉钉/飞书中发送该绑定码 -> 绑定完成”。但不同渠道的发送位置、机器人配置、Webhook/Stream 模式、权限校验都不同。用户到这里会缺少可执行的下一步。

用户影响：

- 接入外部渠道时容易卡住
- 需要反复查文档
- 失败后不知道是配置错误、签名错误还是机器人未连接

建议：

- 按渠道展示差异化步骤
- 显示当前渠道 webhook URL、签名校验状态、最近错误
- 绑定码生成后给出“去哪个会话发送”的具体指引

### 9. 监控页面直接访问 Actuator，未走统一 API 客户端

证据：

- `intellimate-web/src/components/MonitoringPage.tsx:45`

问题：

监控页面用原生 `fetch('/actuator/metrics/...')`，没有通过统一 HTTP 客户端，也没有携带 JWT 或静态 token 逻辑。如果后端对 Actuator 收紧认证，监控页面会静默显示空数据。

用户影响：

- 监控页面可能出现大量 “-”
- 用户不知道是无数据、权限不足还是接口失败

建议：

- 通过统一 API 客户端请求监控数据
- 后端封装 `/api/monitoring`，统一认证和错误格式
- 前端显示权限失败、接口失败、暂无数据三种状态

### 10. 前端控制台日志过多

证据：

- `intellimate-web/src/hooks/useWebSocket.ts:51`
- `intellimate-web/src/hooks/useWebSocket.ts:71`
- `intellimate-web/src/hooks/useWebSocket.ts:228`
- `intellimate-web/src/hooks/useWebSocket.ts:381`

问题：

计划事件和 Agent 绑定流程大量使用 `console.log`。在长时间对话、计划执行和多 Agent 委派时，控制台会被日志刷屏。

用户影响：

- 开发和排障噪声较大
- 可能暴露对话事件、计划内容或内部状态

建议：

- 引入调试开关，例如 `VITE_DEBUG_WS`
- 生产构建默认关闭详细日志
- 关键错误保留为结构化日志

## 四、产品体验层面的缺口

### 1. 首次使用缺少引导

现状：

用户登录后直接进入聊天界面。项目能力很多，包括 Agent、模型供应商、工具、Skills、MCP、记忆、渠道和调度，但没有首次配置向导。

建议：

- 首次进入时检查是否已有可用模型和默认 Agent
- 提供三步引导：配置模型、创建 Agent、发送第一条消息
- 对未配置模型/API Key 的情况给出明确诊断

### 2. 高危工具缺少用户可见风险标签

现状：

工具管理和 Agent 工具选择中没有明确区分普通工具和高危工具。`exec`、文件写入、MCP STDIO、自定义 HTTP 工具的风险等级不同，但用户看到的是平铺的工具能力。

建议：

- 为工具增加风险等级：低、中、高
- 高危工具默认关闭
- 开启高危工具时显示风险说明
- 高危工具调用时默认触发审批

### 3. 多 Agent 委派关系难以理解

现状：

系统支持委派、并行委派和 handoff，但用户需要理解 `can_delegate`、`delegate_agents`、`goal` 等配置。当前配置入口偏工程化。

建议：

- 增加 Agent 关系图
- 显示“谁可以委派给谁”
- 对循环委派、无目标 Agent、目标 Agent 不存在给出校验
- 在聊天界面用时间线解释委派进度

### 4. 错误反馈缺少可操作建议

现状：

部分页面会吞掉错误，例如模型加载、渠道身份加载、记忆数据加载；部分页面只展示原始错误字符串。

建议：

- 统一错误格式和 toast/inline error
- 每类错误附带下一步动作，例如“去模型管理配置 Key”“检查 MySQL”“重试连接 MCP”
- 对后端 401、403、5xx、网络错误分别处理

## 五、工程质量和维护风险

### 1. 循环依赖被配置允许

证据：

- `intellimate-gateway/src/main/resources/application.yml:6`

问题：

`allow-circular-references: true` 说明 Bean 依赖边界可能已经复杂到需要 Spring 放宽限制。长期会增加启动不确定性和测试构造难度。

建议：

- 梳理 gateway、agent、memory 之间的服务边界
- 用接口和事件拆开循环依赖
- 将配置解析、运行时调度、持久化职责进一步隔离

### 2. CORS 配置过宽

证据：

- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/WebConfig.java:12`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/WebSocketRouterConfig.java:26`

问题：

允许所有 Origin 且允许 credentials。生产环境应限制到可信前端域名。

建议：

- 增加 `INTELLIMATE_ALLOWED_ORIGINS`
- 本地开发允许 localhost
- 生产默认拒绝 wildcard credentials

### 3. 前端包体积偏大

验证结果：

`intellimate-web` 构建通过，但主 bundle 约 1.2MB，Vite 提示 chunk 大于 500KB。

建议：

- 对管理页、监控页、代码高亮、图表组件做路由级懒加载
- 将 `react-syntax-highlighter`、`recharts` 拆分 chunk
- 减少首屏聊天页面加载成本

## 六、建议修复顺序

1. 轮换并移除仓库内真实密钥和默认密码
2. 强制生产认证，收紧 CORS
3. 修复 `mvn test` 中 HeartbeatEngine 测试失败
4. 为高危工具增加默认关闭、审批和沙箱边界
5. 完成工具审批前端 UI
6. 修复断线输入提示和实际发送行为不一致
7. 改造登录成功后的 SPA 跳转
8. 优化 Agent 配置保存模型和错误反馈
9. 增加首次使用配置向导
10. 拆分前端大 chunk

