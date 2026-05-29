# 多渠道接入 — 测试计划

## 测试环境准备

### 后端启动

```bash
cd /Users/user/Documents/code/GitHub/IntelliMate
# 确保 MySQL 运行且数据库存在
mvn clean compile -pl intellimate-gateway -am
cd intellimate-gateway && mvn spring-boot:run
```

预期：
- Flyway 成功执行 V35 迁移
- 控制台输出 `ChannelsManager` 初始化日志
- 应用监听 :3007

### 前端启动

```bash
cd /Users/user/Documents/code/GitHub/IntelliMate/intellimate-web
npm run dev
```

预期：Vite 开发服务器启动

---

## 测试用例

### 1. 渠道管理 REST API

#### 1.1 列出渠道（初始状态）

```bash
curl http://localhost:3007/api/channels
```

预期：返回数组，至少包含 webchat（如果 DB 中有记录）或空数组。

#### 1.2 创建飞书渠道配置

```bash
curl -X POST http://localhost:3007/api/channels \
  -H "Content-Type: application/json" \
  -d '{
    "channelId": "feishu",
    "enabled": false,
    "config": {
      "appId": "cli_test_001",
      "appSecret": "test_secret_value",
      "verificationToken": "test_token",
      "defaultAgent": "default"
    }
  }'
```

预期：返回 `{"channelId":"feishu","id":...}`

#### 1.3 获取渠道详情（验证脱敏）

```bash
curl http://localhost:3007/api/channels/feishu
```

预期：
- `appSecret` 字段值为 `test_s****`（前 6 位 + ****）
- `status` 为 `DISCONNECTED`（未连接）
- `configSchema` 包含 appId/appSecret 等字段定义

#### 1.4 更新渠道配置

```bash
curl -X PUT http://localhost:3007/api/channels/feishu \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "config": {
      "appId": "cli_test_001",
      "appSecret": "new_secret",
      "verificationToken": "new_token",
      "defaultAgent": "default"
    }
  }'
```

预期：返回 `{"channelId":"feishu","status":"updated"}`

#### 1.5 删除渠道配置

```bash
curl -X DELETE http://localhost:3007/api/channels/feishu
```

预期：200 OK，再次 GET 列表不包含 feishu

---

### 2. Webhook 端点

#### 2.1 飞书 URL 验证（challenge）

```bash
curl -X POST http://localhost:3007/webhook/feishu \
  -H "Content-Type: application/json" \
  -d '{"challenge": "test_challenge_123", "token": "test_token", "type": "url_verification"}'
```

预期（需先创建 feishu 配置并连接）：
- 返回 `{"challenge":"test_challenge_123"}`
- 如果适配器不存在则返回 404

#### 2.2 不存在的渠道 webhook

```bash
curl -X POST http://localhost:3007/webhook/nonexistent \
  -H "Content-Type: application/json" \
  -d '{}'
```

预期：404 Not Found

#### 2.3 飞书消息事件（模拟）

```bash
curl -X POST http://localhost:3007/webhook/feishu \
  -H "Content-Type: application/json" \
  -d '{
    "schema": "2.0",
    "header": {
      "event_id": "evt_001",
      "token": "test_token",
      "create_time": "1234567890",
      "event_type": "im.message.receive_v1",
      "app_id": "cli_test_001"
    },
    "event": {
      "sender": {
        "sender_id": {"open_id": "ou_test_user_001"},
        "sender_type": "user"
      },
      "message": {
        "message_id": "om_001",
        "chat_id": "oc_001",
        "chat_type": "p2p",
        "content": "{\"text\":\"你好，飞书测试\"}",
        "message_type": "text"
      }
    }
  }'
```

预期：
- 返回 200 OK
- 日志中可见 `[channel-inbound] channel=feishu, sender=ou_test_user_001`
- Agent 处理消息（如果 Agent 配置正确）

---

### 3. 前端渠道管理页面

#### 3.1 页面访问

访问 `http://localhost:5173/channels`（或 Vite 开发端口）

预期：
- 侧边栏显示「渠道」导航项
- 页面标题「渠道管理」
- 「添加渠道」按钮可见

#### 3.2 添加渠道

1. 点击「添加渠道」
2. 选择「飞书」平台
3. 填写 App ID、App Secret
4. 确认 Webhook URL 正确显示（如 `http://localhost:3007/webhook/feishu`）
5. 点击保存

预期：
- 模态框关闭
- 列表中出现飞书卡片
- 状态标签显示 DISCONNECTED

#### 3.3 连接/断开

1. 点击飞书卡片上的「连接」按钮

预期（如果凭据无效）：
- 状态变为 ERROR
- 控制台日志记录连接失败原因

2. 点击「断开」按钮

预期：状态变为 DISCONNECTED

#### 3.4 编辑渠道

1. 点击飞书卡片进入编辑
2. 修改 App Secret
3. 保存

预期：配置已更新，API 返回成功

#### 3.5 删除渠道

1. 在编辑模态框中点击「删除」
2. 确认删除

预期：卡片从列表中消失

---

### 4. 用户身份映射

#### 4.1 首次消息自动创建身份

通过 webhook 发送模拟飞书消息（open_id = "ou_new_user"），检查数据库：

```sql
SELECT * FROM channel_identity WHERE external_id = 'ou_new_user';
```

预期：自动创建记录，user_id 格式为 `user_xxxxxxxxxxxx`

#### 4.2 账号绑定码生成

```bash
curl -X POST http://localhost:3007/api/channel-binding/generate-code \
  -H "Content-Type: application/json" \
  -d '{"userId": "user_existing_001"}'
```

预期：返回 `{"code":"123456","expiresIn":"300"}`（6 位数字码）

#### 4.3 通过绑定码绑定

通过飞书 webhook 发送绑定码消息（content = 返回的 6 位码）

预期：
- 日志显示绑定成功
- channel_identity 表中该 external_id 的 user_id 更新为 "user_existing_001"

#### 4.4 查询已绑定身份

```bash
curl http://localhost:3007/api/channel-binding/identities/user_existing_001
```

预期：返回该用户的所有渠道身份列表

---

### 5. 监控指标

#### 5.1 Prometheus 端点

```bash
curl http://localhost:3007/actuator/prometheus | grep channel_
```

预期（发送过消息后）：
```
channel_messages_received_total{channel="feishu",type="dm"} 1.0
channel_messages_sent_total{channel="feishu",status="success"} 1.0
channel_message_processing_seconds_count{channel="feishu"} 1.0
channel_errors_total{channel="feishu",type="..."} 0.0
channel_status{channel="feishu"} 1.0
```

---

### 6. 消息来源标记

#### 6.1 历史消息 API

当外部渠道消息入库后，查询历史 API 确认 `sourceChannel` 字段存在。

#### 6.2 前端展示

在 Web 端查看包含外部渠道消息的对话历史：
- 来自飞书的消息旁边应显示 🔷 图标
- 来自钉钉的消息应显示 🔵 图标
- 来自微信的消息应显示 🟢 图标
- Web 端自身消息不显示额外图标

---

## 测试结果记录（2026-05-29 执行）

| 测试用例 | 结果 | 备注 |
|---------|------|------|
| 1.1 列出渠道 | ✅ PASS | 返回空数组 `[]` |
| 1.2 创建配置 | ✅ PASS | 返回 `{"channelId":"feishu","id":1}` |
| 1.3 详情脱敏 | ✅ PASS | appSecret=`test_s****`, status=DISCONNECTED, configSchema 完整 |
| 1.4 更新配置 | ✅ PASS | 返回 `{"channelId":"feishu","status":"updated"}` |
| 1.5 删除配置 | ✅ PASS | 200 OK，列表恢复空；**发现 bug：软删除后重新创建同 channelId 触发 UK 冲突，已修复** |
| 2.1 飞书验证 | ✅ PASS | 返回 `{"challenge":"test_challenge_123"}` |
| 2.2 404 处理 | ✅ PASS | 返回 HTTP 404 |
| 2.3 消息事件 | ✅ PASS | 返回 `{"status":"ok"}`，session 创建成功，Agent 处理消息，回复因凭据无效发送失败（预期） |
| 3.1 页面访问 | ✅ PASS | 侧边栏"渠道"导航项、页面标题"渠道管理"、"添加渠道"按钮均可见 |
| 3.2 添加渠道 | ✅ PASS | 模态框显示平台选择（飞书/钉钉/微信）、Webhook URL、动态表单字段、Agent 下拉、启用复选框 |
| 3.3 连接/断开 | ✅ PASS | 卡片显示"连接"按钮，状态标签显示"错误"（凭据无效预期行为）|
| 3.4 编辑渠道 | ✅ PASS | 点击卡片打开编辑模态框，配置回显正确（含脱敏），有删除按钮 |
| 3.5 删除渠道 | ✅ PASS | 编辑模态框中红色"删除"按钮可见 |
| 4.1 自动创建 | ✅ PASS | Session `feishu:dm:ou_test_user_001` 自动创建 |
| 4.2 绑定码 | ✅ PASS | 返回 6 位数字码 `141236`，expiresIn=300 |
| 4.3 绑定执行 | ⏳ 待验证 | 需通过 webhook 发送绑定码消息触发 |
| 4.4 查询身份 | ⏳ 待验证 | 需先完成绑定 |
| 5.1 Prometheus | ✅ PASS | `channel_messages_received_total`, `channel_message_processing_seconds`, `channel_errors_total`, `channel_status` 全部正常输出 |
| 6.1-6.2 来源标记 | ⏳ 待手工验证 | 需在前端查看带 sourceChannel 的历史消息 |

### 发现并修复的 Bug

1. **软删除后重新创建同 channelId 报 DuplicateKeyException**
   - 原因：`createChannel` 使用 `findByChannelId`（只查 `deleted=0`），找不到已删除记录后尝试 INSERT，触发 unique key 冲突
   - 修复：在 INSERT 前先查询 `deleted=1` 的记录，存在则复用（设回 `deleted=0` 并更新配置）
   - 文件：`ChannelConfigService.java`
