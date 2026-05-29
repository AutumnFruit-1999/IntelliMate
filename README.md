# IntelliMate

智能 AI 助手框架 —— 自托管的多 Agent AI 平台，基于 Spring Boot WebFlux + React 构建。

## 核心特性

- **多 Agent 运行时** — 创建多个独立 Agent，各自拥有专属模型、工具、技能、记忆和委派规则
- **实时对话** — 基于 WebSocket 的流式对话，支持工具调用可视化、计划执行和取消
- **多渠道集成** — 统一接入钉钉（Stream/Webhook）、飞书、微信公众号，支持跨渠道用户绑定与消息实时同步
- **工具生态** — 内置文件/执行/搜索工具 + 自定义 HTTP 工具 + MCP 服务器工具 + 远程桥接工具
- **技能系统** — 兼容 AgentSkills 的 SKILL.md 定义，支持版本管理、分组、Git 导入/同步和文件树浏览
- **类脑记忆** — 工作记忆 + 记忆整合 + 长期记忆（情景/语义/程序性），带遗忘与检索机制，每个 Agent 独立配置
- **计划模式** — Agent 可起草多步骤计划，经用户审批后逐步执行
- **主动式 Agent** — 心跳引擎驱动的定时自主提示和任务提醒，支持推送到外部渠道
- **定时调度** — Cron / 固定频率任务（Agent 提示、记忆维护、数据清理、HTTP 回调）
- **用户认证** — JWT 令牌认证，支持注册/登录/会话管理
- **可观测性** — Prometheus/Micrometer 指标、Swagger UI、实时监控仪表盘

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端运行时** | Java 21, Spring Boot 3.4, Spring WebFlux |
| **AI 框架** | Spring AI 1.0, Spring AI Alibaba (DashScope/Qwen) |
| **数据层** | MySQL + Spring Data R2DBC, Flyway 迁移 |
| **MCP 集成** | MCP SDK 0.11, spring-ai-starter-mcp-client-webflux |
| **前端** | React 19, TypeScript 5, Vite 6, Tailwind CSS 4 |
| **状态管理** | Zustand 5 |
| **图表** | Recharts |
| **API 文档** | springdoc-openapi (Swagger UI) |
| **监控** | Micrometer + Prometheus |

## 项目结构

```
IntelliMate/
├── intellimate-core/         # 共享协议模型、配置、异常
├── intellimate-channel-api/  # 渠道适配器 SPI（ChannelAdapter 接口 + 抽象基类）
├── intellimate-memory/       # 记忆子系统（工作记忆、长期记忆、整合、遗忘）
├── intellimate-agent/        # Agent 运行时循环、工具引擎、LLM 集成
├── intellimate-gateway/      # 主应用（HTTP/WS/渠道 Webhook 服务器、数据库、调度器）
│   └── channel/              # 渠道适配器实现（钉钉/飞书/微信）
├── intellimate-web/          # React 前端管理/对话 UI
├── intellimate-local/        # Node.js CLI 桥接，用于本地工具执行
├── skills/                   # 内置平台技能（天气、Excel、Word、macOS 自动化等）
└── docs/                     # 设计文档、接入指南和测试计划
```

## 快速开始

### 前置条件

- Java 21+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0+（或 Docker）

### 数据库配置

创建 MySQL 数据库：

```sql
CREATE DATABASE intellimate DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

或使用 Docker：

```bash
docker run -d --name intellimate-mysql \
  -e MYSQL_ROOT_PASSWORD=yourpassword \
  -e MYSQL_DATABASE=intellimate \
  -p 3306:3306 mysql:8.0
```

### 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_HOST` | 数据库主机 | `localhost` |
| `MYSQL_PORT` | 数据库端口 | `3306` |
| `MYSQL_DATABASE` | 数据库名 | `intellimate` |
| `MYSQL_USERNAME` | 数据库用户名 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | — |
| `DASHSCOPE_API_KEY` | 阿里 DashScope API Key | — |
| `INTELLIMATE_AUTH_TOKEN` | WebSocket/API 认证令牌 | —（留空为开发模式） |
| `INTELLIMATE_CRYPTO_KEY` | API Key 加密密钥 | —（留空则明文存储） |
| `INTELLIMATE_JWT_SECRET` | JWT 签名密钥 | —（留空则自动生成） |
| `INTELLIMATE_SKILLS_DIR` | 技能文件根目录 | `../skills/` |
| `INTELLIMATE_PORT` | 后端端口 | `3007` |

### 一键启动

```bash
./start.sh
```

启动脚本会自动检测并终止占用端口的旧进程，后端运行在 `http://localhost:3007`，前端运行在 `http://localhost:5173`。

### 手动启动

```bash
# 1. 构建
mvn clean install -DskipTests

# 2. 启动后端
mvn spring-boot:run -pl intellimate-gateway

# 3. 启动前端（新终端）
cd intellimate-web
npm install
npm run dev
```

### 本地桥接（可选）

用于在本地机器上执行文件/命令工具：

```bash
cd intellimate-local
npm install && npm run build
npx intellimate-local --server ws://localhost:3007/api/bridge/connect --name my-node
```

## 多渠道集成

IntelliMate 支持通过统一的渠道适配器 SPI 接入多个 IM 平台：

| 渠道 | 接入方式 | 状态 |
|------|----------|------|
| **Web** | WebSocket 实时通信 | 内置 |
| **钉钉** | Stream 模式（推荐） / Webhook 模式 | 已实现 |
| **飞书** | Webhook 事件订阅 | 已实现 |
| **微信公众号** | 消息推送 + 加密验证 | 已实现 |

### 跨渠道能力

- **用户绑定** — 6 位配对码将外部渠道用户映射到统一身份
- **消息同步** — Web 端与外部渠道之间实时双向消息同步
- **统一会话** — 同一用户跨渠道共享对话上下文和记忆

详细接入指南：[docs/guides/channel-integration-guide.md](docs/guides/channel-integration-guide.md)

## 功能页面

| 路由 | 功能 |
|------|------|
| `/chat` | 主对话界面，支持计划面板和流式输出 |
| `/agents` | Agent 管理卡片（创建、配置、删除） |
| `/skills` | 技能管理（CRUD、Git 导入、编辑器、版本历史） |
| `/tools` | MCP 服务器 + 自定义 HTTP 工具 |
| `/models` | 模型供应商 + 模型定义管理 |
| `/channels` | 渠道管理（创建、编辑、删除外部渠道配置） |
| `/memory` | 记忆观测 + 长期记忆查看 + 按 Agent 配置 |
| `/history` | 会话历史列表 + 归档对话查看 + 搜索 |
| `/scheduler` | 定时任务仪表盘 |
| `/monitoring` | 实时 Prometheus 指标监控 |

## API 概览

所有 REST API 基于 `http://localhost:3007`：

| 路径 | 说明 |
|------|------|
| `/api/auth` | 用户注册/登录（JWT） |
| `/api/agents` | Agent CRUD |
| `/api/skills` | 技能管理（含 Git 导入/同步） |
| `/api/mcp-servers` | MCP 服务器管理 |
| `/api/model-providers` | 模型供应商管理 |
| `/api/models` | 模型定义列表 |
| `/api/channels` | 渠道配置管理 |
| `/api/channel-binding` | 跨渠道用户绑定 |
| `/api/memory` | 记忆配置与查询（支持按 Agent 隔离） |
| `/api/sessions` | 会话历史、归档、搜索 |
| `/api/scheduled-jobs` | 定时任务管理 |
| `/api/tasks/{agentId}` | Agent 任务（TODO）管理 |
| `/api/plans` | 计划查询 |
| `/api/heartbeat` | 心跳配置与触发 |
| `/api/webhook/{channelId}` | 外部渠道 Webhook 入口 |
| `/ws` | WebSocket 对话协议 |
| `/swagger-ui.html` | API 文档 |
| `/actuator/prometheus` | Prometheus 指标 |

## 支持的模型供应商

| 类型 | 说明 |
|------|------|
| `DASHSCOPE` | 阿里云 DashScope（Qwen 系列） |
| `OPENAI_COMPATIBLE` | OpenAI 兼容接口（ZenMux、商汤等） |
| `ANTHROPIC` | Anthropic Claude |
| `DEEPSEEK` | DeepSeek |

## 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      intellimate-web                         │
│               React 19 / Vite 6 / Tailwind 4                │
│     Chat  Agents  Skills  Models  Channels  Memory  ...     │
└─────────────────────────┬───────────────────────────────────┘
                          │ WebSocket /ws + REST /api
┌─────────────────────────▼───────────────────────────────────┐
│                  intellimate-gateway                         │
│            Spring Boot 3.4 WebFlux (:3007)                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │ Message  │ │Scheduler │ │Heartbeat │ │   Channel    │   │
│  │ Pipeline │ │  Engine  │ │  Engine  │ │   Manager    │   │
│  └────┬─────┘ └──────────┘ └──────────┘ └──────┬───────┘   │
│       │         R2DBC MySQL + Flyway            │           │
└───────┼─────────────────────────────────────────┼───────────┘
        │                                         │
┌───────▼──────────────────────────┐    ┌─────────▼──────────┐
│        intellimate-agent         │    │ Channel Adapters   │
│  AgentRuntime → ToolsEngine     │    │ DingTalk (Stream)  │
│   ┌────────────┐ ┌──────────┐   │    │ Feishu (Webhook)   │
│   │Spring AI   │ │MCP Client│   │    │ WeChat (Official)  │
│   └────────────┘ └──────────┘   │    └────────────────────┘
└───────┬──────────────────────────┘
        │
┌───────▼──────────────────────────┐
│        intellimate-memory        │
│  WorkingMemory → Consolidation   │
│  → LongTermMemory (Episodic /    │
│    Semantic / Procedural)        │
└──────────────────────────────────┘
```

## 记忆系统

IntelliMate 实现了类脑的多层记忆架构：

| 层级 | 说明 | 持久性 |
|------|------|--------|
| **工作记忆** | 当前对话上下文窗口，自动管理 token 预算 | 会话内（可从 DB 重建） |
| **记忆整合** | 上下文溢出时自动摘要压缩，提取关键事实 | 触发时执行 |
| **长期记忆** | 情景/语义/程序性记忆，支持检索和遗忘 | 持久化到数据库 |

- `/clear` 命令会将当前对话自动归档，并从对话记录中提取情景记忆存入长期记忆
- 每个 Agent 可独立配置记忆参数（token 预算、整合阈值、遗忘策略等）
- 记忆观测面板提供实时 token 使用、chunk 列表和整合日志

## 开发指南

### 编译

```bash
mvn compile                          # 编译所有模块
mvn compile -pl intellimate-gateway  # 仅编译网关模块
```

### 前端类型检查

```bash
cd intellimate-web && npx tsc --noEmit
```

### 数据库迁移

Flyway 迁移文件位于 `intellimate-gateway/src/main/resources/db/migration/`，应用启动时自动执行。

### API 文档

启动后端后访问 `http://localhost:3007/swagger-ui.html` 查看交互式 API 文档。

### 添加新渠道

1. 在 `intellimate-gateway/src/main/java/.../channel/` 下创建适配器，继承 `AbstractChannelAdapter`
2. 实现 `parseWebhook()`、`sendMessage()`、`verifySignature()` 方法
3. 在渠道管理页面配置渠道参数

## License

私有项目，保留所有权利。
