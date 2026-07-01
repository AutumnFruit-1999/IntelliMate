# IntelliMate 问题修复技术方案

日期：2026-06-08

关联文档：

- `docs/project/potential-issues-and-user-defects-2026-06-08.md`

目标：

- 收敛当前项目的安全风险、测试失败和用户体验缺陷
- 给出可拆分、可验证、可回滚的技术修改方案
- 作为后续开发任务拆分和评审依据

## 一、修复原则

1. 安全默认关闭：生产环境不允许使用隐式开发模式。
2. 高危工具显式授权：命令执行、文件写入、MCP STDIO、Bridge 本地执行必须可审计、可审批、可限制。
3. 用户行为一致：界面提示、按钮状态和实际逻辑必须一致。
4. 配置可诊断：失败时告诉用户原因和下一步动作。
5. 修改后同步测试：每个后端行为变更必须更新单元测试或集成测试。

## 二、修复阶段划分

### Phase 0：立即止血

范围：

- 移除仓库内默认真实密钥
- 修复当前 `mvn test` 失败
- 明确生产认证配置

验收：

- `mvn test` 通过
- `npm run build` in `intellimate-web` 通过
- `npm run build` in `intellimate-local` 通过
- 启动日志能明确显示认证模式

### Phase 1：安全基线

范围：

- 认证强制化
- CORS 收紧
- 密码哈希升级
- 高危工具默认关闭和审批

验收：

- 未认证请求无法访问 `/api`
- 未认证 WebSocket 无法连接 `/ws`
- 默认 Agent 不包含高危工具
- 用户开启高危工具时必须看到风险提示

### Phase 2：用户体验修复

范围：

- 工具审批前端 UI
- 断线输入行为修复
- 登录后 SPA 跳转
- Agent 配置页保存状态拆分
- 模型、渠道、监控页面错误反馈

验收：

- 工具审批可批准、拒绝、修改参数
- 断线提示与发送行为一致
- 登录后不刷新页面
- 各配置 Tab 能明确显示保存状态

### Phase 3：架构和维护优化

范围：

- 解除循环依赖
- 前端代码拆包
- 监控 API 统一认证
- 首次使用引导

验收：

- 移除 `allow-circular-references: true`
- 首屏 bundle 明显下降
- 监控页面错误状态可诊断

## 三、后端修改方案

### 1. 配置与密钥

涉及文件：

- `intellimate-gateway/src/main/resources/application.yml`
- `docker-compose.yml`
- 新增 `.env.example`

修改点：

- 删除 `DASHSCOPE_API_KEY` 的默认真实值。
- 删除 MySQL 默认强密码值，改为环境变量必填或开发专用示例值。
- 增加启动配置校验：生产 profile 下缺少关键密钥时启动失败。
- 新增文档化配置项：
  - `INTELLIMATE_REQUIRE_AUTH`
  - `INTELLIMATE_ALLOWED_ORIGINS`
  - `INTELLIMATE_ENV`

建议配置模型：

```yaml
intellimate:
  security:
    require-auth: ${INTELLIMATE_REQUIRE_AUTH:true}
    auth-token: ${INTELLIMATE_AUTH_TOKEN:}
    jwt-secret: ${INTELLIMATE_JWT_SECRET:}
    crypto-key: ${INTELLIMATE_CRYPTO_KEY:}
    allowed-origins: ${INTELLIMATE_ALLOWED_ORIGINS:http://localhost:5173}
```

兼容策略：

- `dev` profile 可允许无 token，但只允许 localhost。
- 默认 profile 按生产安全策略处理，不再静默放行。

测试：

- 缺少生产密钥时启动校验失败。
- dev profile 无 token 时仅 localhost 可访问。
- 配置 allowed origins 后 CORS 生效。

### 2. API 与 WebSocket 认证

涉及文件：

- `ApiAuthFilter.java`
- `SecurityService.java`
- `GatewayWebSocketHandler.java`
- `WebSocketRouterConfig.java`
- `BridgeWebSocketHandler.java`

修改点：

- `ApiAuthFilter` 不再因 `auth-token` 为空直接放行。
- JWT secret 与静态 token 分离，不再复用 `crypto-key`。
- `/ws` 和 `/api` 采用一致认证判断。
- `/api/bridge/connect` 必须校验 Bridge node token，同时限制来源和连接频率。

建议行为：

| 场景 | 行为 |
|---|---|
| dev + localhost + 未配置 token | 允许 |
| dev + 非 localhost + 未配置 token | 拒绝 |
| prod + 未配置 token/JWT secret | 启动失败 |
| prod + token 无效 | 401 或关闭 WebSocket |

测试：

- REST 未认证返回 401。
- WebSocket token 无效时关闭连接。
- dev localhost 可免 token。
- dev 非 localhost 不可免 token。

### 3. 密码哈希升级

涉及文件：

- `AuthController.java`
- `UserEntity.java`
- `V36__users.sql` 或新增迁移

修改点：

- 引入 `PasswordEncoder`，优先使用 bcrypt。
- password hash 字段存储带算法前缀的值，例如 `{bcrypt}...`。
- 兼容旧 SHA-256：登录成功后自动重哈希并更新。
- 密码最小长度改为 8 或更高。
- 增加登录失败限流。

建议新增服务：

- `PasswordService`
- `LoginRateLimiter`

测试：

- 新注册用户密码为 bcrypt。
- 旧 SHA-256 用户可登录，登录后升级为 bcrypt。
- 连续失败触发限流。

### 4. 高危工具护栏

涉及文件：

- `ExecTool.java`
- `FileReadTool.java`
- `FileWriteTool.java`
- `FileEditTool.java`
- `ToolsEngine.java`
- `ToolApprovalGate.java`
- `ToolExecutionPipeline.java`

修改点：

- 增加全局工具安全配置：
  - `intellimate.tools.allowed-paths`
  - `intellimate.tools.blocked-commands`
  - `intellimate.tools.high-risk-tools`
  - `intellimate.tools.default-profile`
- 默认 Agent 不启用 `exec` 和写文件工具。
- 文件工具解析真实路径后必须落在 allowlist 内。
- `exec` 执行前做命令策略检查。
- 高危工具默认进入审批流程。

示例配置：

```yaml
intellimate:
  tools:
    allowed-paths:
      - ${INTELLIMATE_WORKSPACE_DIR:./workspace}
    blocked-commands:
      - "rm -rf /"
      - "curl * | sh"
      - "wget * | sh"
    high-risk-tools:
      - exec
      - writeFile
      - editFile
```

测试：

- 读写 allowlist 外路径返回拒绝。
- blocked command 不执行。
- 高危工具触发 `ApprovalRequired`。
- 审批拒绝时工具不执行。

### 5. Heartbeat 测试失败修复

涉及文件：

- `HeartbeatEngineTest.java`
- 可选：`HeartbeatEngine.java`

问题：

测试 stub 了 `isAgentOnline()`，实际代码调用 `isAgentReachable()`。

修改点：

- 将测试中的 stub 改为 `when(chatInjectionService.isAgentReachable(...)).thenReturn(Mono.just(true))`。
- 增加不可达时跳过心跳的测试。

验收：

```bash
mvn test
```

必须通过。

### 6. 监控 API 统一认证

涉及文件：

- 新增 `MonitoringController.java`
- `MonitoringPage.tsx`
- `httpClient.ts`

修改点：

- 后端提供 `/api/monitoring/metrics` 聚合接口。
- 前端不再直接访问 `/actuator/metrics/...`。
- 保留 Actuator 给运维系统，前端只走受控 API。

测试：

- 未认证访问 `/api/monitoring/metrics` 返回 401。
- 有权限时返回前端需要的聚合指标。
- Actuator 不影响前端错误显示。

## 四、前端修改方案

### 1. 工具审批 UI

涉及文件：

- `useWebSocket.ts`
- `chatStore.ts`
- 新增 `ToolApprovalDialog.tsx`
- `ChatPanel.tsx` 或顶层 `App.tsx`

修改点：

- 处理 `agent.approval_required` 事件。
- 在 store 中保存 pending approval：
  - requestId
  - toolCallId
  - toolName
  - arguments
  - riskLevel
- 弹窗展示工具名、参数、风险说明。
- 提供操作：
  - 批准
  - 拒绝
  - 修改参数后批准
- 发送 `conversation.approve_tool` 请求。

建议事件模型：

```ts
interface PendingToolApproval {
  requestId: string;
  toolCallId: string;
  toolName: string;
  arguments: string;
  createdAt: number;
}
```

验收：

- 高危工具调用时弹出审批框。
- 批准后工具继续执行。
- 拒绝后 Agent 收到拒绝结果。
- 审批弹窗可取消当前对话。

### 2. 断线输入行为修复

涉及文件：

- `ComposeArea.tsx`
- `useWebSocket.ts`
- `chatStore.ts`
- `wsClient.ts`

两种可选方案：

方案 A：不支持离线发送。

- 文案改为“连接已断开，重连后才能发送”。
- 输入仍可编辑，但发送按钮禁用。
- 重连后用户手动发送。

方案 B：支持离线草稿队列。

- 断线时允许点击发送。
- 消息进入 `offlineQueue`。
- WebSocket connected 后自动发送。
- UI 显示待发送数量和可取消操作。

推荐先做方案 A，风险小、改动可控。

验收：

- 断线提示和按钮行为一致。
- 重连后状态正确恢复。

### 3. 登录注册 SPA 化

涉及文件：

- `LoginPage.tsx`
- `authStore.ts`
- `App.tsx`

修改点：

- 移除 `window.location.reload()`。
- 登录成功后直接更新 auth state。
- 根据当前 location 或 redirect 参数跳转。
- 注册成功后可进入首次配置向导。

验收：

- 登录后不刷新页面。
- token 写入后 WebSocket 使用新 token 连接。
- 刷新页面后仍保持登录态。

### 4. Agent 配置保存模型统一

涉及文件：

- `AgentConfigModal.tsx`
- `agentStore.ts`
- `ModelTab.tsx`
- `SkillsTab.tsx`
- 后端 `AgentController.java`

修改点：

- 为每个 Tab 维护独立 dirty 状态。
- Skills 的 `skillsEnabled` 和 `skillGroupsEnabled` 使用一个原子保存接口。
- Model Tab 明确显示保存方式。如果是即时保存，应显示“已保存/保存中/失败”。
- Delegation、Heartbeat、Tasks Tab 独立保存状态，不复用底部全局保存区。

建议 store 结构：

```ts
interface AgentConfigDirtyState {
  context: boolean;
  tools: boolean;
  mcp: boolean;
  skills: boolean;
  model: boolean;
}
```

验收：

- 切换 Tab 不会丢失未保存状态。
- 保存失败时能定位到具体 Tab。
- Skills 保存不再出现部分成功而用户无感知。

### 5. 模型选择错误反馈

涉及文件：

- `ModelSelector.tsx`
- `ModelManagerModal.tsx`
- `modelStore.ts`

修改点：

- 区分三种状态：
  - 加载中
  - 暂无模型
  - 加载失败
- 加载失败显示错误和重试按钮。
- 暂无模型时提供“前往模型管理”入口。

验收：

- 模型接口 500 时显示错误。
- 模型为空时显示配置引导。
- 重试按钮可重新加载。

### 6. 渠道接入引导增强

涉及文件：

- `ChannelsPage.tsx`
- `ChannelConfigModal.tsx`
- 后端 Channel DTO

修改点：

- 按渠道展示接入步骤。
- 显示 webhook URL、Stream 状态、最近错误。
- 绑定码区域按渠道说明在哪里发送绑定码。
- 连接失败时展示 adapter 返回的错误原因。

验收：

- 新用户能在页面完成渠道接入，不需要反复查 README。
- 最近一次连接错误可见。

### 7. 首次使用向导

涉及文件：

- 新增 `OnboardingPage.tsx` 或 `SetupWizard.tsx`
- `App.tsx`
- `agentStore.ts`
- `modelStore.ts`

触发条件：

- 无模型供应商，或
- 无可用模型定义，或
- 无 Agent，或
- 默认 Agent 不可用

向导步骤：

1. 配置模型供应商和 API Key。
2. 创建第一个 Agent。
3. 选择工具安全配置。
4. 发送第一条测试消息。

验收：

- 全新数据库启动后不进入空白聊天体验。
- 用户能清楚知道下一步。

## 五、数据库和迁移

建议新增迁移：

1. 用户密码算法字段或密码哈希格式升级。
2. 工具风险等级表或扩展 `tool_definition` 字段。
3. 渠道连接最近错误字段。
4. Agent 配置原子更新所需字段不一定需要迁移，视现有表结构决定。

示例字段：

```sql
ALTER TABLE user ADD COLUMN password_algo VARCHAR(32) DEFAULT 'sha256';

ALTER TABLE channel_config
  ADD COLUMN last_error TEXT NULL,
  ADD COLUMN last_error_at DATETIME NULL;

ALTER TABLE tool_definition
  ADD COLUMN risk_level VARCHAR(16) DEFAULT 'medium';
```

注意：

- 如果采用 `{bcrypt}` 前缀格式，可不新增 `password_algo`。
- 迁移必须兼容现有数据。

## 六、测试计划

### 后端单元测试

必须覆盖：

- API auth filter
- WebSocket token validation
- JWT service
- Password service
- Tool security guard
- Tool approval gate
- HeartbeatEngine
- MonitoringController

命令：

```bash
mvn test
```

### 前端构建与基础测试

命令：

```bash
cd intellimate-web
npm run build
```

建议新增：

- 工具审批弹窗组件测试
- 登录不刷新页面的行为测试
- ModelSelector 错误态测试
- ComposeArea 断线态测试

### 手工 QA

关键路径：

1. 全新环境首次启动。
2. 注册、登录、退出。
3. 创建 Agent，选择模型。
4. 发送普通消息。
5. 触发高危工具审批。
6. 断线、重连、继续发送。
7. 配置 MCP 服务并测试连接。
8. 创建渠道并生成绑定码。
9. 查看监控页。

## 七、上线与回滚

### 上线顺序

1. 合入测试修复。
2. 合入配置安全改造，但 dev profile 保持本地可用。
3. 合入认证和 CORS 收紧。
4. 合入工具护栏。
5. 合入前端审批和体验修复。
6. 合入首次使用向导和拆包优化。

### 回滚策略

- 安全配置变更需保留 dev profile 回退路径。
- 密码升级采用兼容读写，不做不可逆批量转换。
- 工具护栏支持配置开关，但生产默认开启。
- 前端审批 UI 可独立回滚，不影响后端审批协议。

## 八、任务拆分建议

### 后端任务

1. 修复 HeartbeatEngine 测试。
2. 移除默认密钥并增加配置校验。
3. 重构认证模式。
4. 引入 PasswordService。
5. 增加 ToolSecurityGuard。
6. 新增 MonitoringController。
7. 渠道错误状态持久化。

### 前端任务

1. 工具审批 UI。
2. 断线输入行为修复。
3. 登录注册去刷新。
4. Agent 配置保存状态重构。
5. ModelSelector 错误态。
6. 渠道引导增强。
7. 首次使用向导。
8. 路由级懒加载和 chunk 拆分。

### 文档任务

1. 更新 README 快速启动安全说明。
2. 新增生产部署安全清单。
3. 新增高危工具使用说明。
4. 新增渠道接入故障排查。

## 九、完成定义

满足以下条件才视为本轮修复完成：

- 后端、前端、本地桥接端构建通过。
- 所有后端测试通过。
- 生产环境无默认密钥、无默认放行认证。
- 高危工具默认关闭或需要审批。
- 工具审批前端闭环可用。
- 用户首次使用能完成模型和 Agent 配置。
- 关键错误都有明确用户反馈。

