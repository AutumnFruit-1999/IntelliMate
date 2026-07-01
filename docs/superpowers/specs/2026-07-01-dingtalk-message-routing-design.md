# 钉钉消息路由与绑定体验优化设计

## 概述

解决钉钉机器人「发给谁」的问题：明确消息路由机制，优化用户绑定流程，新增群聊主动推送能力。

## 背景

当前系统通过 `channel_identity` 表维护「统一用户 ID ↔ 外部平台 ID」的映射关系。钉钉机器人发送消息时，依赖此映射确定接收者。

**现有能力**：
- 用户在钉钉与机器人互动后，自动创建 `channel_identity` 记录
- 通过 6 位绑定码将钉钉身份与 Web 账号关联
- `CrossChannelSyncService` 基于身份记录进行跨渠道消息同步

**待解决问题**：
- 绑定码体验粗糙：用户不知道怎么绑、没有实时反馈
- 缺少用户个人绑定信息的展示和管理
- 不支持群聊主动推送
- 渠道配置页缺少群聊管理能力

## 设计原则

1. **绑定即可达**：有 `channel_identity` 记录就能发消息，没有就不发，不做降级
2. **最小改动**：消息路由核心逻辑不变，聚焦绑定体验和群聊推送
3. **用户自服务**：每个用户自己管理自己的绑定，不需要管理员介入

## 一、消息路由机制（无变更，文档化现有行为）

### 1.1 路由决策流程

```
消息需要发到钉钉
  ↓
查 channel_identity（userId + channelId = dingtalk-stream）
  ├── 找到 → 用 externalId 作为 userIds → 调用 oToMessages/batchSend → 发送
  └── 没找到 → 跳过，不做任何处理
```

### 1.2 各场景路由行为

| 场景 | 触发条件 | 路由行为 | 前提 |
|---|---|---|---|
| Web → 钉钉同步 | Web 用户发消息 + AI 回复 | 查绑定身份 → 发到用户的钉钉单聊 | 用户已绑定 |
| 钉钉 → Web 同步 | 钉钉用户给机器人发消息 | 查绑定身份 → WebSocket 推到 Web | 用户已绑定 |
| 主动推送（定向） | 系统/定时任务触发 | 指定 userId → 查身份 → 发单聊 | 目标用户已绑定 |
| 主动推送（群聊） | 系统/定时任务/心跳触发 | 查 Agent 绑定的群 → 发群聊消息 | Agent 已绑定群 |
| 群聊回复 | 群内 @机器人 | 直接回复到原群 | 无需绑定 |
| 未绑定用户 | 任何推送场景 | 跳过 | — |

### 1.3 钉钉机器人能力边界

当前使用的是**企业内部应用机器人（Stream 模式）**，能力如下：

| 能力 | 支持情况 | API |
|---|---|---|
| 接收单聊消息 | ✅ 用户直接给机器人发消息 | Stream SDK `BOT_MESSAGE_TOPIC` |
| 接收群聊消息 | ✅ 用户在群里 @机器人 | 同上 |
| 主动发单聊 | ✅ 需要知道 userId | `POST /v1.0/robot/oToMessages/batchSend` |
| 主动发群聊 | ✅ 需要知道 openConversationId | `POST /v1.0/robot/groupMessages/send` |
| 群聊 @人 | ❌ 服务端 API 不支持 | — |

## 二、绑定码体验优化

### 2.1 绑定码格式容错

**改动位置**：`ChannelPipelineConfig.tryBindingCode()`

当前只接受纯 6 位数字，增加以下格式支持：

| 输入格式 | 示例 | 处理方式 |
|---|---|---|
| 纯数字 | `123456` | 直接匹配（已有） |
| bind 前缀 | `bind 123456` | 去前缀后匹配 |
| 绑定前缀 | `绑定 123456` | 去前缀后匹配 |
| 带空格 | `123 456` | 去空格后匹配 |

实现：在正则匹配前，对输入文本做预处理——去除 `bind`/`绑定` 前缀和所有空格，然后用现有的 6 位数字匹配逻辑。

### 2.2 首次互动引导

**改动位置**：`ChannelPipelineConfig`（入站消息处理链路）

当未绑定 Web 账号的钉钉用户首次与机器人互动（发送的不是绑定码）时，在 Agent 正常回复之后，额外附加一段引导文本：

```
💡 如需将此钉钉账号与 Web 端关联以实现消息同步，请在 Web 端「设置 → 渠道绑定」中生成绑定码，然后发送给我。
```

仅在首次互动时附加一次（可通过 `channel_identity` 记录是否有 `user_id` 关联来判断）。

### 2.3 绑定成功实时通知

**改动位置**：`ChannelPipelineConfig.tryBindingCode()` + `GatewayWebSocketHandler`

绑定成功后：
1. **钉钉端**：回复「绑定成功！你的钉钉账号已与 Web 账号关联，后续消息将自动同步。」（已有）
2. **Web 端**：通过 WebSocket 推送 `binding.success` 事件，前端实时更新绑定状态

事件格式：
```json
{
  "type": "binding.success",
  "payload": {
    "channelId": "dingtalk-stream",
    "externalName": "张三",
    "boundAt": "2026-07-01T17:30:00Z"
  }
}
```

## 三、用户个人绑定信息展示

### 3.1 后端 API

**已有 API**：`GET /api/channel-binding/identities/{userId}`

返回数据增强，确保包含以下字段：

```json
[
  {
    "id": 1,
    "channelId": "dingtalk-stream",
    "externalId": "staff_xxx",
    "externalName": "张三",
    "userId": "user_abc123",
    "createdAt": "2026-07-01T10:00:00Z"
  }
]
```

### 3.2 前端 UI

**位置**：用户设置页，新增「渠道绑定」区域

展示内容（每个绑定一行）：

| 渠道 | 名称 | 绑定时间 | 操作 |
|---|---|---|---|
| 🔷 钉钉 | 张三 | 2026-07-01 | [解绑] |
| 📘 飞书 | 张三 | 2026-06-28 | [解绑] |

功能：
- 显示当前用户所有已绑定的渠道身份
- 支持解绑（调用 `DELETE /api/channel-binding/identities/{id}`）
- 生成绑定码入口 + 操作步骤提示
- 监听 `binding.success` 事件实时更新列表

### 3.3 绑定码生成交互

点击「绑定新渠道」按钮后：
1. 选择渠道类型（钉钉/飞书/微信）
2. 生成 6 位绑定码，显示倒计时（5 分钟）
3. 展示操作步骤：「在钉钉中找到机器人 → 发送绑定码 `123456`」
4. 绑定成功后自动刷新列表

## 四、群聊主动推送

### 4.1 群信息记录

**改动位置**：`DingtalkStreamAdapter.handleBotMessage()` + 新增数据存储

当机器人在群里收到消息时，记录群信息：

```java
// 从 ChatbotMessage 中提取
String openConversationId = message.getConversationId();
String conversationTitle = message.getConversationTitle(); // 群名称
```

使用数据库表持久化，因为群 ↔ Agent 绑定关系需要在重启后保留：

```sql
CREATE TABLE channel_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id VARCHAR(50) NOT NULL,
    group_id VARCHAR(200) NOT NULL,       -- openConversationId
    group_name VARCHAR(200),
    agent_name VARCHAR(100),              -- 绑定的 Agent（可空）
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_channel_group (channel_id, group_id)
);
```

### 4.2 Agent ↔ 群绑定

**改动位置**：新增 REST API

| API | 说明 |
|---|---|
| `GET /api/channels/{channelId}/groups` | 获取该渠道下所有已知群列表 |
| `PUT /api/channels/{channelId}/groups/{groupId}/agent` | 为群绑定 Agent |
| `DELETE /api/channels/{channelId}/groups/{groupId}/agent` | 解绑 Agent |

### 4.3 群聊推送实现

**改动位置**：`ChatInjectionService`（主动推送服务）

现有逻辑：遍历 Agent 关联渠道的所有 `channel_identity` 记录，发送单聊消息。

新增逻辑：同时查询 `channel_group` 表，找到绑定了该 Agent 的群，发送群聊消息。

```
主动推送触发（heartbeat / scheduled）
  ↓
查 channel_group（agent_name = 当前 Agent）
  ├── 找到群 → 用 groupId 调用 groupMessages/send → 群聊推送
  └── 没找到 → 跳过群聊推送
  ↓
（原有的单聊推送逻辑不变）
```

### 4.4 渠道配置页群聊管理 UI

**位置**：渠道配置页（`/channels`），在渠道详情中新增「群聊」Tab

展示内容：

| 群名称 | 群 ID | 绑定 Agent | 加入时间 | 操作 |
|---|---|---|---|---|
| 技术讨论群 | conv_xxx | 默认助手 | 2026-07-01 | [更换 Agent] [解绑] |
| 产品反馈群 | conv_yyy | — | 2026-06-30 | [绑定 Agent] |

功能：
- 列出机器人已加入的所有群
- 为群绑定/更换/解绑 Agent
- 群名称自动更新（每次收到群消息时刷新）

## 五、改动范围总结

### 后端

| 文件 | 改动类型 | 描述 |
|---|---|---|
| `ChannelPipelineConfig` | 修改 | 绑定码格式容错 + 首次互动引导 + 绑定成功 WebSocket 通知 |
| `DingtalkStreamAdapter` | 修改 | 群消息时记录群信息到 `channel_group` |
| `ChatInjectionService` | 修改 | 主动推送增加群聊发送逻辑 |
| `ChannelGroupEntity` | 新增 | 群信息实体 |
| `ChannelGroupRepository` | 新增 | 群信息 R2DBC 仓库 |
| `ChannelGroupService` | 新增 | 群管理业务逻辑 |
| `ChannelGroupController` | 新增 | 群管理 REST API |
| `V{N}__add_channel_group.sql` | 新增 | Flyway 迁移脚本 |

### 前端

| 文件 | 改动类型 | 描述 |
|---|---|---|
| 用户设置页 | 修改/新增 | 渠道绑定区域（列表 + 绑定码生成 + 解绑） |
| `useWebSocket.ts` | 修改 | 处理 `binding.success` 事件 |
| 渠道配置页 | 修改 | 新增「群聊」Tab（群列表 + Agent 绑定管理） |

### 不变的部分

| 部分 | 原因 |
|---|---|
| `CrossChannelSyncService` | 路由逻辑已正确：有身份就发，没有就跳过 |
| `DingtalkStreamAdapter.send()` | 单聊/群聊发送 API 调用已实现 |
| `channel_identity` 表结构 | 满足需求 |
| 绑定码生成/验证核心逻辑 | 只增加格式预处理，核心不变 |

## 六、数据流图

```
┌─────────────┐     绑定码      ┌──────────────────┐
│  Web 用户    │  ──────────→   │ ChannelPipeline  │
│  设置页      │  ←──────────   │ Config           │
│             │  binding.success│                  │
└─────────────┘                 └──────────────────┘
                                        │
                                        ▼
                                ┌──────────────────┐
                                │ channel_identity  │
                                │ (用户绑定记录)     │
                                └──────────────────┘
                                        │
                        ┌───────────────┼───────────────┐
                        ▼               ▼               ▼
              ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
              │ Web→钉钉同步  │ │ 钉钉→Web同步  │ │ 主动推送(DM) │
              │ CrossChannel │ │ CrossChannel │ │ ChatInjection│
              │ SyncService  │ │ SyncService  │ │ Service      │
              └──────────────┘ └──────────────┘ └──────────────┘

                                ┌──────────────────┐
                                │ channel_group     │
                                │ (群绑定记录)       │
                                └──────────────────┘
                                        │
                                        ▼
                              ┌──────────────────┐
                              │ 主动推送(群聊)     │
                              │ ChatInjection     │
                              │ Service           │
                              └──────────────────┘
```

## 七、多账号绑定策略

### 7.1 绑定码竞争（两个钉钉账号发同一个绑定码）

绑定码一次性使用，先到先得。第一个发送者绑定成功后，绑定码即失效，第二个发送者收到「绑定码无效或已过期」的提示。无需改动，已有机制。

### 7.2 一个 Web 用户绑定多个钉钉账号

**允许**。用户可能有多个钉钉账号（如工作号和个人号），每次绑定生成新的 `channel_identity` 记录。消息同步时会发送到所有已绑定的钉钉账号。

### 7.3 一个钉钉账号被多个 Web 用户绑定

**不允许**。如果同一个钉钉 `externalId` 已关联了某个 `userId`，拒绝新的绑定请求。

**实现**：在 `ChannelPipelineConfig.tryBindingCode()` 绑定逻辑中，增加检查：

```
绑定码验证通过
  ↓
查 channel_identity（channelId + externalId）
  ├── 已存在且 userId != 当前绑定目标 userId → 拒绝，回复「该钉钉账号已被其他 Web 账号绑定」
  ├── 已存在且 userId == 当前绑定目标 userId → 回复「已绑定，无需重复操作」
  └── 不存在 → 正常绑定
```

## 八、不在范围内

- 企业通讯录同步（不需要，谁绑定就给谁发）
- 群聊消息跨渠道同步（群消息仍只在群内回复）
- 群聊 @人功能（钉钉服务端 API 不支持）
- 消息队列暂存（未绑定用户的消息不做缓存）
