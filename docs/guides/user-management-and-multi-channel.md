# 用户管理与多渠道集成指南

## 概述

IntelliMate 采用三层渠道架构，支持多平台接入和统一会话管理：

- **channel-api**：SPI 层，定义 `ChannelAdapter` 接口
- **core**：标准化模型层（`InboundEnvelope`、`OutboundMessage`、`SessionKey`）
- **gateway**：生命周期管理、消息管道、身份绑定、REST API

---

## 1. 用户管理

### 1.1 用户体系

系统存在两个用户 ID 概念：

| 概念 | 类型 | 来源 | 用途 |
|------|------|------|------|
| DB 用户 ID | `Long` | `users.id` | Web 登录、JWT Subject |
| 统一用户 ID | `String` | `channel_identity.user_id` | 跨渠道会话身份标识（如 `user_a1b2c3d4e5f6`） |

Web 注册用户会自动生成一条 `channel_identity` 记录（`channel_id='webchat'`）。外部渠道首次发消息的用户会自动创建新的统一 ID。

### 1.2 认证机制

#### API Token 模式

通过环境变量 `INTELLIMATE_AUTH_TOKEN` 配置静态令牌：

- 配置为空时：所有 API 无需认证（开发模式）
- 配置非空时：所有 `/api/**` 请求需携带 `Authorization: Bearer <token>`

#### JWT 模式

用户通过 `/api/auth/login` 登录获取 JWT：

- 有效期：7 天
- 签名密钥优先级：`INTELLIMATE_CRYPTO_KEY` → `INTELLIMATE_AUTH_TOKEN` → 开发默认值
- Claims：`sub` = 用户 ID，`username` 字段

### 1.3 用户 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/auth/register` | 注册新用户 |
| `POST` | `/api/auth/login` | 用户登录 |
| `GET` | `/api/auth/me` | 获取当前用户信息（需 JWT） |

**注册请求体：**

```json
{
  "username": "admin",
  "password": "admin123",
  "displayName": "管理员"
}
```

**登录响应：**

```json
{
  "token": "eyJhbGci...",
  "userId": 1,
  "username": "admin",
  "displayName": "管理员",
  "unifiedUserId": "user_11904b06bdfc"
}
```

密码要求：至少 4 位，使用 SHA-256 哈希存储。

---

## 2. 多渠道集成

### 2.1 支持的渠道

| 渠道 ID | 适配器 | 接入方式 | 说明 |
|---------|--------|----------|------|
| `webchat` | `WebChatAdapter` | WebSocket | Web 前端直连，无需 webhook |
| `feishu` | `FeishuAdapter` | Webhook 回调 | 飞书机器人 |
| `dingtalk` | `DingtalkAdapter` | Webhook 回调 | 钉钉 Outgoing 机器人 |
| `dingtalk-stream` | `DingtalkStreamAdapter` | Stream 长连接 | 钉钉 Stream 模式，无需公网地址 |
| `wechat` | `WeChatOfficialAdapter` | Webhook 回调 | 微信公众号 |

### 2.2 渠道配置管理

渠道配置存储在数据库 `channel_config` 表中（非 YAML），通过 REST API 管理。

#### 渠道管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/channels` | 列出所有渠道及状态 |
| `GET` | `/api/channels/{channelId}` | 单个渠道详情 |
| `POST` | `/api/channels` | 创建渠道配置 |
| `PUT` | `/api/channels/{channelId}` | 更新渠道配置 |
| `DELETE` | `/api/channels/{channelId}` | 删除渠道（软删除） |
| `POST` | `/api/channels/{channelId}/connect` | 手动连接 |
| `POST` | `/api/channels/{channelId}/disconnect` | 手动断开 |

#### 各渠道配置参数

**飞书（Feishu）：**

```json
{
  "appId": "cli_xxx",
  "appSecret": "xxx",
  "encryptKey": "可选, 事件加密密钥",
  "verificationToken": "可选, 验证令牌",
  "defaultAgent": "可选, 该渠道默认 agent"
}
```

**钉钉 Stream 模式（DingTalk Stream）：**

```json
{
  "appKey": "xxx",
  "appSecret": "xxx",
  "defaultAgent": "可选"
}
```

**钉钉 Webhook 模式（DingTalk）：**

```json
{
  "appKey": "xxx",
  "appSecret": "xxx",
  "signSecret": "可选, 签名密钥",
  "mode": "outgoing_robot 或 enterprise_app",
  "defaultAgent": "可选"
}
```

**微信公众号（WeChat）：**

```json
{
  "appId": "xxx",
  "appSecret": "xxx",
  "token": "可选, 消息校验 Token",
  "encodingAesKey": "可选, 消息加解密密钥",
  "defaultAgent": "可选"
}
```

### 2.3 Webhook 端点

外部平台回调地址格式：

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/webhook/{channelId}` | URL 验证（微信 echostr 等） |
| `POST` | `/webhook/{channelId}` | 消息回调（JSON/XML） |

示例：飞书回调地址配置为 `https://your-domain.com/webhook/feishu`

---

## 3. 消息流转

### 3.1 外部渠道消息处理流程

```
平台消息 → Webhook/Stream 接收
  → ChannelAdapter 解析 → InboundEnvelope
  → ChannelsManager.deliverInbound
  → ChannelPipelineConfig 入站处理
      ├─ [6位绑定码?] → 执行账号绑定 → 回复 "绑定成功"
      └─ 正常消息 → ChannelIdentityService.resolveUserId
          ├─ 单聊: SessionKey 重写为 ("unified", "dm", userId)
          └─ 群聊: 保持原始 SessionKey
      → 查找渠道默认 Agent
      → MessagePipeline.processInbound
          ├─ 命令处理 (/help, /reset, /clear 等)
          └─ Agent 调用 → 生成回复
  → OutboundMessage(原始 SessionKey, 回复文本)
  → ChannelAdapter.send → 平台 API 发送回复
```

**关键设计**：处理阶段使用统一 SessionKey，回复阶段使用原始渠道 SessionKey，确保消息路由正确。

### 3.2 WebSocket 消息流程

```
前端 WebSocket /ws?token=<JWT>
  → 验证 Token → 绑定 userId
  → MessagePipeline.processRequest
      → resolveUserId("webchat", jwtUserId)
      → SessionKey("unified", "dm", unifiedUserId)
      → AgentRuntime 流式回复 → WebSocket 帧推送
```

### 3.3 群聊与单聊的区别

| 维度 | 单聊（DM） | 群聊（Group） |
|------|-----------|--------------|
| SessionKey | `unified:dm:{userId}` | `{channel}:group:{groupId}` |
| 会话统一 | 跨渠道共享同一会话 | 每个平台独立会话 |
| 发送 API | 各平台单聊 API | 各平台群消息 API |
| 身份映射 | 自动映射到统一 userId | 保持平台原始群 ID |

---

## 4. 跨渠道身份绑定

### 4.1 自动映射

用户首次从任一渠道发消息时，系统自动在 `channel_identity` 表中创建映射记录。每个渠道的外部 ID 对应一个统一 userId。

### 4.2 手动绑定（关联多渠道账号）

当用户需要将多个渠道身份关联到同一个统一账号时：

**步骤：**

1. 获取用户的 `unifiedUserId`（登录响应或已有身份记录）
2. 调用 `POST /api/channel-binding/generate-code` 生成 6 位绑定码（有效期 5 分钟）
3. 用户在目标渠道（如钉钉/飞书）中发送该 6 位数字
4. 系统自动拦截、完成绑定、回复 "绑定成功"

**绑定管理 API：**

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/channel-binding/generate-code` | 生成绑定码 |
| `GET` | `/api/channel-binding/identities/{userId}` | 查看已绑定渠道 |
| `DELETE` | `/api/channel-binding/identities/{identityId}` | 解除绑定 |

### 4.3 身份表结构

```sql
CREATE TABLE channel_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,          -- 统一用户 ID
    channel_id VARCHAR(32) NOT NULL,       -- 渠道标识
    external_id VARCHAR(128) NOT NULL,     -- 平台用户 ID
    external_name VARCHAR(128),            -- 平台用户名
    bound_at TIMESTAMP DEFAULT NOW(),
    UNIQUE KEY uk_channel_external (channel_id, external_id),
    INDEX idx_user (user_id)
);
```

---

## 5. 会话管理

### 5.1 会话模型

每个 SessionKey 对应一个唯一会话，存储在 `session` 表中。单聊经过统一化后共享同一个会话。

### 5.2 会话历史 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/sessions/{agentName}/messages` | 活跃会话消息（支持分页） |
| `POST` | `/api/sessions/{agentName}/clear` | 归档当前会话、持久化记忆、开启新会话 |
| `GET` | `/api/sessions/{agentName}/archived` | 历史归档会话列表 |
| `GET` | `/api/sessions/by-id/{sessionId}/messages` | 指定会话的消息 |
| `DELETE` | `/api/sessions/by-id/{sessionId}` | 删除归档会话 |
| `GET` | `/api/sessions/{agentName}/search` | 关键词搜索 |

### 5.3 /clear 命令行为

执行 `/clear`（通过 WebSocket 命令或 HTTP API）时，系统依次执行：

1. **记忆持久化** — 从数据库 `transcript_message` 表读取当前会话的所有消息，提取用户话题摘要，生成情景记忆（episodic）存入 `agent_memory` 表
2. **会话归档** — 将当前活跃 session 标记为 `archived`，生成标题
3. **新建会话** — 创建新的活跃 session，上下文重置
4. **前端清空** — 清除对话消息列表、工作记忆观测面板、计划面板

**设计原则**：记忆持久化直接从数据库读取 transcript，不依赖内存状态，即使服务重启后 `/clear` 也能正确产生长期记忆。

### 5.3 主动推送（Proactive Messages）

系统支持通过 Heartbeat 和定时任务向用户主动推送消息：

- **WebSocket**：通过 `SessionRegistry` 推送到在线 Web 用户
- **外部渠道**：通过各渠道适配器的 `send()` 方法推送到钉钉/飞书等

---

## 6. 环境变量参考

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `INTELLIMATE_PORT` | `3007` | 服务端口 |
| `INTELLIMATE_AUTH_TOKEN` | 空 | API 静态令牌（空则不启用 API 认证） |
| `INTELLIMATE_CRYPTO_KEY` | 空 | JWT 签名密钥 |
| `MYSQL_HOST` | `localhost` | MySQL 地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `intellimate` | 数据库名 |
| `MYSQL_USERNAME` | `root` | 数据库用户名 |
| `MYSQL_PASSWORD` | — | 数据库密码 |
| `DASHSCOPE_API_KEY` | — | 阿里云 DashScope API Key |

---

## 7. 快速开始

### 7.1 首次启动

```bash
./start.sh
```

启动后：
- 后端：`http://localhost:3007`
- 前端：`http://localhost:5173`

### 7.2 注册管理员

```bash
curl -X POST http://localhost:3007/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","displayName":"管理员"}'
```

### 7.3 配置渠道

以钉钉 Stream 为例：

```bash
curl -X POST http://localhost:3007/api/channels \
  -H "Content-Type: application/json" \
  -d '{
    "channelId": "dingtalk-stream",
    "enabled": true,
    "config": {
      "appKey": "your-app-key",
      "appSecret": "your-app-secret",
      "defaultAgent": "shangtang"
    }
  }'
```

### 7.4 跨渠道绑定

```bash
# 生成绑定码
curl -X POST http://localhost:3007/api/channel-binding/generate-code \
  -H "Content-Type: application/json" \
  -d '{"userId":"user_11904b06bdfc"}'

# 响应: {"code":"123456","expiresIn":300}
# 然后在目标渠道中发送 "123456" 即可完成绑定
```

---

## 8. 记忆系统

### 8.1 记忆层级

| 层级 | 存储位置 | 持久性 | 说明 |
|------|----------|--------|------|
| 工作记忆 | 内存 + DB 重建 | 会话内 | 当前对话上下文窗口，管理 token 预算 |
| 长期记忆 | `agent_memory` 表 | 持久 | 情景/语义/程序性记忆 |
| 记忆配置 | `memory_config` 表 | 持久 | 按 Agent 独立配置 |

### 8.2 工作记忆观测

记忆观测面板（`/memory`）显示当前工作记忆状态：

- **在线时**：从 Agent 运行时获取实时快照
- **页面加载时**：从 `transcript_message` 表重建快照视图（token 估算 + chunk 列表）
- **`/clear` 后**：前端立即清空显示为空状态

### 8.3 每 Agent 独立配置

记忆配置通过 `memory_config` 表管理，支持按 `agent_name` 隔离：

- 每个 Agent 可独立设置 token 预算、整合阈值、遗忘策略等
- 未配置的 Agent 继承 `_global_` 全局默认值
- 前端切换 Agent 时自动加载对应配置

**配置 API**（均支持 `?agentName=xxx` 参数）：

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/memory/config` | 获取配置 |
| `POST` | `/api/memory/config` | 更新配置 |
| `POST` | `/api/memory/config/reset` | 重置为默认 |

---

## 9. 数据库表清单

| 表名 | 用途 |
|------|------|
| `users` | Web 用户账户 |
| `channel_identity` | 跨渠道身份映射 |
| `channel_config` | 渠道连接配置 |
| `session` | 对话会话 |
| `transcript_message` | 消息记录（含 `source_channel`） |
| `agent_memory` | 长期记忆存储 |
| `memory_config` | 记忆参数配置（按 Agent 隔离） |
| `allowlist_entry` | 渠道发送者白名单 |
| `pairing_request` | DM 配对请求 |
