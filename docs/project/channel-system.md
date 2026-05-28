# 外部渠道系统

## 概述

外部渠道系统是 IntelliMate 接入第三方即时通讯平台的扩展框架。通过统一的 ChannelAdapter 接口和标准化的消息模型（InboundEnvelope / OutboundMessage），系统可以将微信、钉钉、Slack、飞书等平台的用户消息接入 Agent 处理，并将 Agent 回复推送回对应平台。

当前系统已完成接口定义、数据模型设计和基础管道搭建，内置了 WebChat 适配器作为 Web 端的通道。外部平台适配器的具体实现尚未开发，但整体架构已为接入做好准备。

## 架构设计

渠道系统采用 SPI（Service Provider Interface）+ 适配器 + 管理器的三层模式。

### intellimate-channel-api 模块

独立的 API 模块，定义了渠道适配器的标准接口，不依赖 Gateway 的具体实现。

ChannelAdapter 接口规定了每个渠道适配器必须实现的能力：

- getChannelId()：返回渠道标识符，如 "webchat"、"wechat"、"dingtalk"
- connect(config)：使用配置信息建立连接
- disconnect()：断开连接
- send(OutboundMessage)：向该渠道发送消息
- onMessage(handler)：注册入站消息处理器
- getConfigSchemaClass()：返回配置结构类，用于 UI 动态渲染配置表单
- getStatus()：返回当前连接状态

ChannelStatus 枚举定义了连接生命周期：DISCONNECTED → CONNECTING → CONNECTED → RECONNECTING → ERROR。

### 消息模型

消息模型定义在 intellimate-core 模块中，是渠道无关的统一格式。

InboundEnvelope（入站信封）包含：

- sessionKey：三段式会话标识（channelId + contextType + contextId）
- senderId / senderName：发送者信息
- text：消息文本
- attachments：附件列表（文件名、MIME 类型、URL、大小）
- timestamp：消息时间
- rawPayload：原始平台数据，用于调试或特殊处理

OutboundMessage（出站消息）包含：

- sessionKey：目标会话
- text：回复文本
- attachments：附件列表
- replyToMessageId：引用的消息 ID

### SessionKey 三段式标识

SessionKey 是连接渠道消息与后端会话的关键：

- channelId：渠道标识，如 "webchat"、"wechat"、"feishu"
- contextType：上下文类型，"dm"（私聊）、"group"（群组）或 "channel"（频道）
- contextId：平台特定的对话标识，如微信的 openId、Slack 的 channel ID

组合键格式为 "channelId:contextType:contextId"，用于会话查找和创建。

## ChannelsManager

ChannelsManager 是 Gateway 中的渠道生命周期管理器，负责：

### 自动发现

通过 Spring 依赖注入自动收集所有 ChannelAdapter Bean，以 channelId 为键存入内存 Map。开发者只需将新适配器标记为 @Component，管理器就会自动发现。

### 启动连接

应用启动时读取 channel_config 表中 enabled 为 true 的渠道配置，逐一调用对应适配器的 connect 方法建立连接。

### 出站路由

发送消息时根据 OutboundMessage 的 sessionKey.channelId 找到对应适配器，检查连接状态后调用 send 方法。如果适配器不存在或未连接，抛出 ChannelException。

### 入站处理器

setInboundHandler 方法用于注册全局入站消息处理器，将该回调分发到所有已注册适配器的 onMessage 方法。收到消息后由处理器负责路由到 MessagePipeline 进行 Agent 处理。

### 关闭清理

应用关闭时逐一断开已连接的适配器。

## WebChatAdapter

WebChatAdapter 是当前唯一的适配器实现，服务于 Web 端 WebSocket 通道。

特点：

- channelId 固定为 "webchat"
- connect 后始终处于 CONNECTED 状态
- send 是空操作（Web 端的出站消息直接通过 WebSocket 推送，不经过渠道管道）
- deliverInbound 方法已定义但未被调用（Web 端消息直接由 GatewayWebSocketHandler 进入 MessagePipeline）

这个适配器的存在更多是为了架构完整性，确保 Web 通道也在 ChannelsManager 的管理范围内。

## 数据库配置

### channel_config 表

存储各渠道的连接配置：

- id：主键
- channel_id：渠道标识（唯一约束）
- enabled：是否在启动时自动连接
- config_json：JSON 格式的适配器专属配置（如 appId、secret、token 等）
- created_at / updated_at：时间戳
- deleted：软删除标记

目前没有提供 REST API 管理渠道配置，需要直接操作数据库。

## Webhook 端点

WebhookController 提供了接收外部平台回调的 HTTP 端点，路径为 /webhook/{channelId}。

支持三种入口：

- GET /webhook/{channelId}：平台验证请求，如微信的 URL 验证会回显 echostr 参数
- POST /webhook/{channelId}（JSON）：JSON 格式的消息回调
- POST /webhook/{channelId}（XML）：XML 格式的消息回调（如微信）

当前行为：

1. 检查对应 channelId 的适配器是否存在（不存在返回 404）
2. 写入审计日志
3. 返回通用成功响应

尚未实现的部分：

- 平台签名验证
- 消息体解析和转换
- 转发到适配器或创建 InboundEnvelope
- 触发 Agent 处理

## 安全基础设施

系统预置了为外部渠道设计的安全组件，虽然尚未串联使用：

### 发送者白名单

allowlist_entry 表存储按渠道分组的允许发送者列表。SecurityService.isAllowed 方法可检查某渠道的某发送者是否在白名单中。

### DM 配对验证

pairing_request 表和 DmPairingService 实现了 6 位配对码机制。当 DM 配对要求开启时，陌生发送者需要通过配对验证后才能与 Agent 对话。

这些安全组件的接口和实现已就绪，待外部渠道适配器实现后即可启用。

## 外部渠道接入路径

基于现有架构，接入一个新的外部平台需要以下步骤：

### 实现适配器

创建平台专属的 ChannelAdapter 实现类（如 WeChatAdapter），标记为 @Component，处理：

- connect：初始化平台 SDK 或建立长连接
- send：调用平台 API 发送消息，将 OutboundMessage 转换为平台格式
- onMessage：在收到平台消息时将其转换为 InboundEnvelope 并交给注册的处理器

### 串联入站处理

在某个配置类中调用 channelsManager.setInboundHandler，将入站消息路由到 SessionManager 和 MessagePipeline。

### 完善 Webhook

扩展 WebhookController 或在适配器内部处理平台回调，包括签名验证、消息解析、InboundEnvelope 构建。

### 配置数据库

在 channel_config 表中插入平台配置记录（appId、secret、token 等），enabled 设为 true。

### 启用安全策略

根据需要配置发送者白名单和 DM 配对验证。

## 各平台特性参考

| 平台 | 回调格式 | 关键差异 |
|------|---------|---------|
| 微信 | GET 验证 + POST XML | 消息加解密、XML 解析、accessToken 刷新 |
| 钉钉 | POST JSON + HMAC 签名 | 机器人与应用消息格式不同 |
| Slack | POST JSON + URL 验证挑战 | Events API、OAuth Bot Token、thread_ts 回复 |
| 飞书 | POST JSON + 挑战验证 | 事件订阅、tenant token |

## 当前 Web 端消息流

当前 Web 端并不走渠道管道，而是直接通过 WebSocket：

```
前端 WebSocket (/ws)
  → GatewayWebSocketHandler
  → MessagePipeline.processRequest("conversation.message")
  → channelId 默认为 "webchat"，contextType 默认为 "dm"
  → SessionManager.getOrCreate(SessionKey)
  → AgentRuntime.dispatch(...)
```

channelId 参数在 MessagePipeline 中被解析用于会话标识，但消息不经过 ChannelsManager 路由。

## 设计要点

SPI 解耦：channel-api 模块仅定义接口，不依赖 Gateway 实现，适配器可以独立开发和测试。

消息标准化：所有平台的消息都转换为统一的 InboundEnvelope / OutboundMessage，上层业务逻辑无需感知平台差异。

安全前置：白名单和配对验证机制已就绪，可在接入外部渠道时直接启用，无需额外开发。

渐进式接入：现有架构不要求一次性接入所有平台，可以逐个实现适配器，ChannelsManager 会自动发现并管理。
