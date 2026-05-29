# 多渠道接入设计

## 概述

在现有 ChannelAdapter SPI 架构基础上，补全入站管道、实现平台适配器、新增渠道管理 UI，让用户能通过飞书、钉钉、微信等 IM 平台直接与 Agent 对话，并实现跨渠道统一会话。

## 需求

- 第一批对接国内三大 IM 平台：飞书、钉钉、微信生态
- 覆盖场景：个人自用、企业内部、对外客服
- 消息能力：先纯文本，架构预留富媒体和交互式消息扩展
- 微信生态先做架构抽象，具体形态后续再定
- 跨渠道统一会话：同一用户在不同渠道的对话共享上下文和历史
- Web 管理后台：可视化配置渠道、监控连接状态

## 方案选型

评估了三种方案后选择方案 A：补全现有管道。

| 方案 | 思路 | 结论 |
|------|------|------|
| A 补全现有管道 | 在现有 SPI 上查漏补缺 | **采用**：改动最小、复用设计、风险低 |
| B 独立渠道网关 | 抽离为独立微服务 | 弃选：对当前规模过度设计 |
| C 轻量代理转发 | 中间代理归一化后转发 | 弃选：多一层运维、与 SPI 设计不统一 |

## 架构设计

### 入站链路

打通当前断裂的 webhook 入站流程：

```
外部平台 webhook
  → WebhookController (/webhook/{channelId})
  → ChannelAdapter.handleWebhook(request)
    ├─ 验签 (signature verification)
    ├─ 解析消息体 → InboundEnvelope
    └─ 平台验证请求直接返回 (URL verification challenge)
  → ChannelsManager.deliverInbound(envelope)
  → MessagePipeline.processInbound(envelope)
  → SessionManager.getOrCreate(sessionKey)
  → AgentRuntime.dispatch(...)
  → 回复生成 → ChannelsManager.sendOutbound(outboundMessage)
  → ChannelAdapter.send(outboundMessage)
  → 平台 API 发送消息
```

### 出站回推

Agent 的流式回复收集完整后通过 `ChannelsManager.sendOutbound()` → 对应适配器的 `send()` 方法回推到平台 API。IM 平台不支持增量推送，必须等完整回复生成后一次性发送。

### ChannelAdapter SPI 扩展

在现有接口基础上新增方法：

```java
public interface ChannelAdapter {
    // 已有
    String getChannelId();
    void connect(ChannelConfig config);
    void disconnect();
    void send(OutboundMessage message);
    void onMessage(Consumer<InboundEnvelope> handler);
    ChannelStatus getStatus();

    // 新增
    WebhookResponse handleWebhook(WebhookRequest request);
    Set<MessageType> supportedMessageTypes();
    JsonNode getConfigSchema();
}
```

- `handleWebhook`：处理平台 webhook 回调，负责验签、消息解析、入站分发，返回平台期望的响应
- `supportedMessageTypes`：声明适配器支持的消息类型，供 UI 展示
- `getConfigSchema`：返回配置表单的 JSON Schema，供前端动态渲染

### MessagePipeline 新增入站入口

```java
public Mono<Void> processInbound(InboundEnvelope envelope) {
    SessionKey key = envelope.getSessionKey();
    return sessionManager.getOrCreate(key)
        .flatMap(session -> agentRuntime.dispatch(session, envelope.getText()))
        .flatMap(reply -> channelsManager.sendOutbound(
            OutboundMessage.of(key, reply.getText())
        ));
}
```

## 适配器实现模式

### 基类

```java
public abstract class AbstractChannelAdapter implements ChannelAdapter {
    protected ChannelStatus status = DISCONNECTED;
    protected ChannelConfig config;
    protected Consumer<InboundEnvelope> inboundHandler;

    protected abstract void doConnect(ChannelConfig config);
    protected abstract void doDisconnect();
    protected abstract void doSend(OutboundMessage message);
    protected abstract InboundEnvelope parseInbound(WebhookRequest request);
    protected abstract boolean verifySignature(WebhookRequest request);
    protected abstract String handleVerification(WebhookRequest request);

    @Override
    public final WebhookResponse handleWebhook(WebhookRequest request) {
        if (isVerificationRequest(request)) {
            return WebhookResponse.ok(handleVerification(request));
        }
        if (!verifySignature(request)) {
            return WebhookResponse.unauthorized();
        }
        InboundEnvelope envelope = parseInbound(request);
        if (inboundHandler != null) {
            inboundHandler.accept(envelope);
        }
        return WebhookResponse.ok();
    }
}
```

### 飞书适配器

第一个实现目标。使用飞书官方 SDK `oapi-sdk-java`。

职责：
- connect：初始化 LarkClient（appId + appSecret）
- handleWebhook：验证事件签名（encryptKey）、解析事件 JSON
- parseInbound：提取 open_id 作为 senderId、chat_id/p2p 作为 contextId/contextType
- send：调用飞书发消息 API（im/v1/messages）

### 钉钉适配器

使用钉钉官方 SDK。支持两种模式：
- 企业内部应用（事件订阅）
- Outgoing 机器人（webhook 回调）

验签采用 HMAC-SHA256。

### 微信适配器

设计为可扩展的子类体系：

```java
public abstract class AbstractWeChatAdapter extends AbstractChannelAdapter {
    protected WeChatCrypto crypto;        // 消息加解密
    protected AccessTokenManager tokenManager; // token 刷新
    // XML 解析、通用消息转换
}
```

具体形态（公众号、企业微信等）各自继承扩展，第一版先实现架构基类，具体形态后续选定。

### 适配器注册

通过 Spring `@Component` 自动发现，`ChannelsManager` 自动收集所有 `ChannelAdapter` Bean。开发者只需新建适配器类标记 `@Component`，无需额外注册配置。

## 跨渠道统一会话

### 用户身份层

引入用户身份映射，将不同渠道的外部身份关联到统一的 IntelliMate 用户。

```
IntelliMate User (userId: "user_001")
  绑定身份:
    - feishu:   open_id_abc
    - dingtalk: staff_id_xyz
    - wechat:   openid_123
    - webchat:  web_session_456
```

### 会话模型

会话以「用户 + Agent」为维度，而非以「渠道 + 上下文」为维度：

- **私聊**：`userId:agentName` = 一个会话，跨渠道共享
- **群聊**：`group:{platform}:{groupId}:agentName` = 独立会话（群天然属于某平台）

### 身份绑定流程

1. 新用户首次从某渠道发消息 → 查询 `channel_identity` 表
2. 已绑定 → 路由到已有用户的会话
3. 未绑定 → 创建新 IntelliMate 用户 + 绑定记录
4. 跨渠道绑定：用户在 Web 端登录后，从新渠道发送 6 位配对码（复用 `DmPairingService`），系统将渠道身份绑定到该用户

### Agent 路由

- 渠道配置中指定默认 Agent（`config_json.defaultAgent`）
- 私聊固定使用渠道绑定的 Agent
- 用户可在对话中切换 Agent（发送 `/agent xxx`）

### 消息来源标记

每条消息记录来源渠道（`source_channel` 字段），Web 端用小图标显示消息来自哪个渠道。

## 渠道管理 REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/channels` | 列出所有渠道及状态 |
| GET | `/api/channels/{channelId}` | 获取渠道详情 |
| POST | `/api/channels` | 创建渠道配置 |
| PUT | `/api/channels/{channelId}` | 更新渠道配置 |
| DELETE | `/api/channels/{channelId}` | 软删除渠道配置 |
| POST | `/api/channels/{channelId}/connect` | 手动连接 |
| POST | `/api/channels/{channelId}/disconnect` | 手动断开 |
| POST | `/api/channels/{channelId}/test` | 测试连接有效性 |
| GET | `/api/channels/{channelId}/config-schema` | 获取配置表单 JSON Schema |

### 响应格式

```json
{
  "channelId": "feishu",
  "displayName": "飞书",
  "status": "CONNECTED",
  "enabled": true,
  "config": {
    "appId": "cli_a1...",
    "appSecret": "****",
    "encryptKey": "****",
    "verificationToken": "****",
    "defaultAgent": "default"
  },
  "stats": {
    "messagesReceived": 1234,
    "messagesSent": 1200,
    "lastMessageAt": "2026-05-29T14:00:00Z",
    "errors24h": 3
  },
  "connectedAt": "2026-05-29T10:00:00Z"
}
```

### 敏感字段处理

- 写入时明文存储（后续可增加加密）
- 读取时对 `secret`/`token`/`key` 类字段脱敏（前 6 位 + `****`）
- 编辑时未修改的敏感字段不覆盖

## 前端渠道管理页面

路由：`/channels`，侧边栏新增「渠道」入口。

### 页面结构

```
/channels
├── 渠道列表（卡片式）
│   ├── 平台图标 + 名称 + 状态标签 + 消息统计
│   ├── 点击进入详情/编辑
│   └── 「添加渠道」按钮
├── 添加/编辑（抽屉/模态框）
│   ├── 选择平台类型
│   ├── 动态配置表单（基于 config-schema）
│   ├── 测试连接
│   └── 保存
└── 渠道详情
    ├── 连接状态 + 操作按钮
    ├── Webhook URL（供用户复制到平台后台）
    ├── 接入指引
    └── 消息统计
```

### 技术要点

- 动态表单：根据 JSON Schema 自动渲染，无需为每个平台写专用组件
- 状态实时更新：通过 WebSocket 推送 `channel.status_changed` 事件
- 新增 `channelStore`（Zustand）管理渠道状态
- 引导式配置：每个平台卡片包含简短接入指引

## 错误处理与监控

### 错误分类

| 错误类型 | 处理方式 |
|---------|---------|
| 平台暂时不可用 | 指数退避重试（3 次），失败后标记 RECONNECTING |
| 凭据失效 | 自动刷新 token，失败标记 ERROR + 告警 |
| 消息发送失败 | 记录失败日志，不重试 |
| 验签失败 | 返回 401，记录安全审计 |
| 消息解析失败 | 回复"暂不支持该消息类型"，记录 WARN |
| Agent 处理超时 | 先回复"思考中..."，完成后主动推送 |

### 平台回复时限

所有平台统一采用异步处理：Webhook 立即返回 200，Agent 回复通过平台 API 主动推送。对于必须同步返回的场景，超时后返回占位消息。

### Token 管理

`AccessTokenManager` 统一管理各平台 token，提前 5 分钟刷新避免边界过期，刷新失败触发告警。

### 监控指标

集成 Micrometer：

```
channel_messages_received_total{channel, type}
channel_messages_sent_total{channel, status}
channel_message_processing_seconds{channel}
channel_api_call_seconds{channel, api}
channel_errors_total{channel, type}
channel_status{channel}
```

### 告警规则

- 渠道断连超过 5 分钟
- 错误率超过 10%
- Token 刷新连续失败

## 数据库变更

### 新增表

```sql
CREATE TABLE channel_identity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(32) NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    external_name VARCHAR(128),
    bound_at TIMESTAMP DEFAULT NOW(),
    UNIQUE KEY uk_channel_external (channel_id, external_id),
    INDEX idx_user (user_id)
);

CREATE TABLE channel_message_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_id VARCHAR(32) NOT NULL,
    direction ENUM('inbound', 'outbound') NOT NULL,
    user_id VARCHAR(64),
    external_id VARCHAR(128),
    message_text TEXT,
    status ENUM('success', 'failed', 'pending') DEFAULT 'success',
    error_message VARCHAR(512),
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_channel_time (channel_id, created_at)
);
```

### 变更现有表

```sql
ALTER TABLE conversation_message
    ADD COLUMN source_channel VARCHAR(32) DEFAULT 'webchat';
```

## 实施顺序

### 阶段 1：管道打通 + 管理 API

1. 补全入站链路（WebhookController → MessagePipeline）
2. 实现 `AbstractChannelAdapter` 基类
3. 实现渠道管理 REST API + `ChannelConfigService`
4. 数据库迁移（channel_identity、channel_message_log、source_channel）
5. 基础单元测试

### 阶段 2：飞书适配器 + 前端管理页

1. 实现 `FeishuAdapter`（事件订阅、消息收发、验签）
2. 前端「渠道管理」页面（列表 + 配置 + 状态）
3. 端到端测试：飞书 → Agent → 飞书回复
4. 用户身份映射 + 跨渠道会话

### 阶段 3：钉钉适配器

1. 实现 `DingtalkAdapter`
2. 复用阶段 2 的管理 UI 和会话机制
3. 端到端测试

### 阶段 4：微信适配器 + 监控完善

1. 实现 `AbstractWeChatAdapter` + 第一个具体形态
2. 完善监控指标和告警
3. 账号绑定功能（配对码）

## 向后兼容

### Web 端不受影响

现有 Web 端（webchat）的 WebSocket 直连流程保持不变。Web 端仍然通过 `GatewayWebSocketHandler → MessagePipeline.processRequest` 处理消息，不经过渠道管道。webchat 在身份体系中作为一个特殊渠道存在，Web 登录用户自动拥有 webchat 身份绑定。

### 会话模型迁移

当前 SessionKey 为 `channelId:contextType:contextId`（如 `webchat:dm:default`）。新模型以 `userId:agentName` 为维度。

迁移策略：
- 内部实现改为基于 userId 查找/创建会话
- 外部接口（WebSocket 协议）暂不变动，Web 端仍发送 `channelId: "webchat"`
- MessagePipeline 在入口处做 userId 解析：Web 端从 token 中提取 userId，外部渠道从 channel_identity 查询 userId
- 现有会话数据通过迁移脚本关联到 userId（webchat 渠道的 contextId 直接映射为 userId）

## 扩展性考虑

- **富媒体消息**：`InboundEnvelope.attachments` 和 `OutboundMessage.attachments` 已预留，后续适配器实现时启用
- **交互式消息**：可通过扩展 `OutboundMessage` 增加 `card`/`interactive` 字段，适配器根据平台能力转换
- **新平台接入**：只需实现 `AbstractChannelAdapter` 子类 + 标记 `@Component`，管理器自动发现
- **独立部署**：如果未来渠道流量增大，适配器可以平滑迁移到独立微服务（方案 B 演进路径）
