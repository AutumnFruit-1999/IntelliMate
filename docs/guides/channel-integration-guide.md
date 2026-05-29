# 渠道对接指南

本文档介绍如何将 IntelliMate 与飞书、钉钉、微信公众号对接，实现用户通过 IM 平台直接与 AI Agent 对话。

## 前置条件

- IntelliMate 后端已部署并可公网访问（HTTPS）
- 后端地址示例：`https://your-domain.com`
- Webhook 回调地址格式：`https://your-domain.com/webhook/{channelId}`

## 管理界面

IntelliMate Web 管理后台提供可视化的渠道配置功能：

1. 登录 Web 管理后台
2. 在侧边栏「管理」区域点击「渠道」
3. 点击「添加渠道」按钮
4. 选择对应平台并填写配置

配置完成后可在管理页面实时查看渠道连接状态、启用/禁用渠道、编辑或删除配置。

---

## 飞书对接

### 1. 创建飞书应用

1. 登录 [飞书开放平台](https://open.feishu.cn/app)
2. 创建「企业自建应用」
3. 记录 **App ID** 和 **App Secret**

### 2. 配置机器人能力

1. 进入应用 → 「添加应用能力」→ 选择「机器人」
2. 进入「事件订阅」配置页
3. 设置 **请求地址**：
   ```
   https://your-domain.com/webhook/feishu
   ```
4. 设置 **Verification Token**（飞书自动生成，复制下来填入 IntelliMate）
5. 如需加密传输，记录 **Encrypt Key**

### 3. 订阅事件

在「事件订阅」页面，添加以下事件：

| 事件 | 用途 |
|------|------|
| `im.message.receive_v1` | 接收用户消息 |

### 4. 配置 IntelliMate

在渠道管理页面填写：

| 字段 | 说明 |
|------|------|
| App ID | 飞书开放平台的应用 App ID |
| App Secret | 飞书开放平台的应用 App Secret |
| Verification Token | 事件订阅页面显示的 Verification Token |
| Encrypt Key | （可选）事件加密密钥 |
| 默认 Agent | 处理消息的 Agent 名称，留空使用 default |

### 5. 发布应用

1. 在飞书开放平台提交应用审核
2. 审核通过后，用户可在飞书中搜索并使用机器人
3. 对私聊消息，机器人直接响应
4. 对群聊消息，需 @机器人 触发

### 验证流程

配置完成后，IntelliMate 会自动处理飞书的 URL 验证请求（challenge）。在飞书中给机器人发送消息，应能收到 AI 回复。

---

## 钉钉对接

### 1. 创建钉钉应用

1. 登录 [钉钉开放平台](https://open-dev.dingtalk.com/)
2. 创建「企业内部应用」或「第三方企业应用」
3. 记录 **App Key** (ClientId) 和 **App Secret** (ClientSecret)

### 2. 配置机器人

1. 进入应用 → 「应用功能」→ 「机器人」
2. 开启机器人配置
3. 设置 **消息接收地址**：
   ```
   https://your-domain.com/webhook/dingtalk
   ```
4. 设置签名验证 — 勾选「加签」，记录 **签名密钥**

### 3. 配置 IntelliMate

在渠道管理页面填写：

| 字段 | 说明 |
|------|------|
| App Key | 钉钉应用的 ClientId |
| App Secret | 钉钉应用的 ClientSecret |
| 签名密钥 | 机器人安全设置中的加签密钥 |
| 默认 Agent | 处理消息的 Agent 名称，留空使用 default |

### 4. 发布上线

1. 在钉钉开放平台提交应用发布
2. 发布后，用户可在钉钉中搜索并添加机器人
3. 支持单聊和群聊（@机器人）

### Outgoing 机器人（快速接入）

如果只需群聊场景，也可使用 Outgoing 机器人：

1. 在钉钉群设置 → 「智能群助手」→ 添加自定义机器人
2. 安全设置选择「加签」，记录密钥
3. 设置 Webhook 地址为 `https://your-domain.com/webhook/dingtalk`
4. 在 IntelliMate 配置中填入签名密钥

---

## 微信公众号对接

### 1. 注册公众号

1. 登录 [微信公众平台](https://mp.weixin.qq.com/)
2. 注册并认证服务号（订阅号功能受限）
3. 记录 **AppID** 和 **AppSecret**

### 2. 服务器配置

1. 进入公众号 → 「设置与开发」→ 「基本配置」
2. 在「服务器配置」区域：
   - **URL**：`https://your-domain.com/webhook/wechat-official`
   - **Token**：自定义一个字符串（需与 IntelliMate 配置一致）
   - **EncodingAESKey**：随机生成或自定义（用于消息加解密）
   - **消息加解密方式**：建议选择「安全模式」
3. 点击「提交」，IntelliMate 会自动处理 URL 验证

### 3. 配置 IntelliMate

在渠道管理页面填写：

| 字段 | 说明 |
|------|------|
| App ID | 公众号的 AppID |
| App Secret | 公众号的 AppSecret |
| Token | 服务器配置中自定义的 Token |
| EncodingAESKey | 服务器配置中的消息加解密密钥 |
| 默认 Agent | 处理消息的 Agent 名称，留空使用 default |

### 4. 注意事项

- 微信公众号要求服务器在 **5 秒内响应**，IntelliMate 会异步处理消息并通过客服消息接口回复
- 需要在公众号后台开启「客服接口」权限
- 未认证的订阅号功能受限，建议使用已认证的服务号

---

## 跨渠道统一会话

IntelliMate 支持同一用户跨渠道共享对话上下文。

### 工作原理

每个渠道平台有自己的用户 ID（如飞书 open_id、钉钉 staffId、微信 openid），IntelliMate 通过「身份绑定」将不同平台的用户 ID 映射到同一个内部用户。

### 首次使用

用户首次通过某渠道发送消息时，系统自动创建临时身份。此时会话与其他渠道隔离。

### 账号绑定

若用户希望跨渠道共享对话历史：

1. 在 IntelliMate Web 端登录，进入「账号绑定」
2. 生成绑定码（6 位数字，有效期 5 分钟）
3. 在飞书/钉钉/微信中向机器人发送该绑定码
4. 绑定成功后，所有渠道共享同一对话上下文

### 效果

- 在飞书中与 Agent 的对话，在 Web 端和微信中也能看到
- Agent 记住用户偏好和上下文，不因切换渠道而丢失

---

## 监控与排错

### 连接状态

在渠道管理页面，每个渠道卡片显示实时状态：

| 状态 | 含义 |
|------|------|
| CONNECTED | 正常连接，可收发消息 |
| DISCONNECTED | 未连接，需手动连接 |
| ERROR | 连接错误，检查凭据或网络 |

### Prometheus 指标

IntelliMate 暴露以下渠道相关指标：

```
channel_messages_received_total{channel="feishu",type="dm"}
channel_messages_sent_total{channel="feishu",status="success"}
channel_message_processing_seconds{channel="feishu"}
channel_errors_total{channel="feishu",type="..."}
channel_status{channel="feishu"}  # 1=connected, 0=disconnected
```

访问 `/actuator/prometheus` 获取完整指标。

### 常见问题

| 问题 | 排查方向 |
|------|---------|
| Webhook 验证失败 | 检查 Token/签名密钥是否一致 |
| 消息收不到 | 确认应用已发布、事件已订阅、URL 可公网访问 |
| 回复超时 | 检查 Agent 响应时间，微信要求 5s 内响应 |
| 连接状态 ERROR | 检查 App Secret 是否正确、应用是否有权限 |
| 跨渠道不共享 | 确认已完成账号绑定 |

---

## REST API 参考

### 渠道管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/channels` | 列出所有渠道配置 |
| POST | `/api/channels` | 创建渠道配置 |
| GET | `/api/channels/{channelId}` | 获取渠道详情（含 configSchema） |
| PUT | `/api/channels/{channelId}` | 更新渠道配置 |
| DELETE | `/api/channels/{channelId}` | 删除渠道配置 |
| POST | `/api/channels/{channelId}/connect` | 连接渠道 |
| POST | `/api/channels/{channelId}/disconnect` | 断开渠道 |

### 账号绑定

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/channel-binding/generate-code` | 生成绑定码 |
| GET | `/api/channel-binding/identities/{userId}` | 查询用户已绑定身份 |

### 创建渠道示例

```bash
curl -X POST https://your-domain.com/api/channels \
  -H "Content-Type: application/json" \
  -d '{
    "channelId": "feishu",
    "enabled": true,
    "config": {
      "appId": "cli_xxxxxxxx",
      "appSecret": "your_app_secret",
      "verificationToken": "your_token",
      "defaultAgent": "default"
    }
  }'
```
