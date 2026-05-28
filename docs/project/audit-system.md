# 审计日志系统

## 概述

审计日志系统记录 IntelliMate 运行过程中的关键操作事件，用于运维追踪、问题诊断和安全审计。系统采用写入即忘（fire-and-forget）模式，审计日志的写入不会阻塞主业务流程，写入失败也不会影响正常功能。

## 数据模型

### audit_log 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| action | VARCHAR(64) | 操作类型标识 |
| actor | VARCHAR(256) | 操作发起者 |
| session_id | BIGINT | 关联的会话 ID（可选） |
| detail | TEXT | 操作详情或载荷摘要 |
| created_at | DATETIME | 记录时间 |

索引：(action, created_at) 用于按操作类型查询，(session_id) 用于按会话查询。

## AuditService

AuditService 是审计日志的唯一写入入口，提供一个核心方法：

log(action, actor, sessionId, detail)

- 创建 AuditLogEntity 实体
- 通过 R2DBC 响应式写入数据库
- 错误处理策略：记录 ERROR 级别日志后静默忽略（onErrorComplete），确保审计写入失败不影响主流程

## 已记录的操作类型

### command

用户通过聊天发送的斜杠命令（如 /plan、/reset 等）。

- actor：WebSocket 会话 ID
- session_id：当前会话 ID
- detail：完整命令文本

### user_message

用户发送的普通聊天消息。

- actor：WebSocket 会话 ID
- session_id：当前会话 ID
- detail：消息内容，截断至 200 字符

### agent_response

Agent 的回复消息。

- actor："agent"
- session_id：当前会话 ID
- detail："length=N"（回复字符长度）

### webhook_verify

外部渠道 Webhook 的验证请求（GET）。

- actor：渠道 ID（如 "wechat"）
- session_id：null
- detail：请求的查询参数字符串

### webhook_callback

外部渠道 Webhook 的 JSON 格式消息回调（POST）。

- actor：渠道 ID
- session_id：null
- detail："keys=[...]"（请求体的顶层字段名列表）

### webhook_xml_callback

外部渠道 Webhook 的 XML 格式消息回调（POST）。

- actor：渠道 ID
- session_id：null
- detail："bodyLen=N"（请求体长度）

## 查询能力

### Repository 方法

AuditLogRepository 提供按会话查询的方法：

findBySessionIdOrderByCreatedAtDesc(sessionId)

返回指定会话的所有审计记录，按时间倒序排列。此外继承 ReactiveCrudRepository 的标准 CRUD 方法（findAll、findById 等）。

### REST API

当前没有提供审计日志的 REST 查询端点。审计记录只能通过直接查询数据库或在代码中调用 Repository 方法获取。

## 写入时机

审计日志在以下位置写入：

### MessagePipeline

- 用户发送斜杠命令时写入 command 记录
- 用户发送普通消息时写入 user_message 记录
- Agent 回复保存到数据库后写入 agent_response 记录

### WebhookController

- 收到 GET 验证请求时写入 webhook_verify 记录
- 收到 POST JSON 回调时写入 webhook_callback 记录
- 收到 POST XML 回调时写入 webhook_xml_callback 记录

## 设计特点

无阻塞保证：AuditService 的写入操作不会阻塞调用方。即使数据库连接异常，主业务流程也能正常继续。

最小化载荷：审计记录只保存必要的摘要信息（如消息长度、字段名列表），不存储完整的消息体或请求内容，避免审计表过度膨胀。

会话关联：通过 session_id 字段将审计记录关联到具体会话，方便按会话维度追踪用户操作轨迹。

## 潜在扩展方向

当前审计覆盖范围聚焦于消息流和 Webhook 入口。以下操作尚未纳入审计：

- 工具执行（tool.exec）
- 会话重置和清理（session.reset）
- 计划操作（plan.create / plan.approve / plan.cancel）
- Bridge 节点连接和断开
- Agent 配置变更
- 认证失败
- 委派和移交事件

这些可以通过在对应业务代码中调用 AuditService.log 来补充，无需修改审计系统本身的架构。
