# IntelliMate 变更记录

> 格式：时间 | 类型 | 描述 | 后端改动 | 前端改动

---

## 2026-03-12 Skills P1 + P2 功能实现

**类型**：Feature / Enhancement

### P1 增强功能

#### `get_skill_content` 内置工具
- `SkillContentProvider.java` (agent/skills)：新增 `readSkillContent(String skillName)` 方法
- `SkillContentProviderImpl.java` (gateway/service)：实现 `readSkillContent()`，优先文件系统，退而 DB
- 新建 `GetSkillContentTool.java` (agent/tools)：使用 `@Tool` 注解，Agent 可通过 tool call 获取 Skill 完整内容
- `ToolAutoConfiguration.java`：有条件注册 `GetSkillContentTool`（仅当 SkillContentProvider 存在时）
- `ToolGroup.java`：新增 `SKILLS("Skills", Set.of("getSkillContent"))` 分组

#### SKILL.md 导入/导出
- `SkillDefinitionController.java`：新增 `GET /api/skills/{id}/export`（导出 SKILL.md 文本含 frontmatter）和 `GET /api/skills/{id}/export/zip`（打包 Skill 整个目录为 zip）
- `SkillFileService.java`：新增 `zipSkillDirectory()` 方法，使用 `ZipOutputStream`
- 新建 `skillImporter.ts` (web/lib)：`parseSkillMd()` 解析 YAML frontmatter、`exportSkillMd()` 生成标准格式、`downloadSkillMd()` 触发下载
- `api.ts`：新增 `exportSkillMdApi()`、`exportSkillZip()` 函数
- `SkillManagerModal.tsx`：工具栏新增"导入 SKILL.md"按钮（textarea 粘贴 → 解析 → 预填充 SkillEditor）；每个 Skill 卡片新增导出下拉（.md / .zip）

#### 标签过滤
- `SkillManagerModal.tsx`：列表上方新增标签按钮组（全部 + 各标签），点击筛选/取消
- `SkillsTab.tsx`：同样新增标签过滤功能

#### 内置 Skills 包
- 新建 4 个 SKILL.md 资源文件 `builtin-skills/{code-reviewer,readme-writer,research-report,unit-test-gen}/SKILL.md`
- 新建 `BuiltinSkillsLoader.java` (gateway/service)：`ApplicationRunner`，启动时扫描 classpath，检查 DB 不存在则创建，metadata 标记 `{"builtin": true}`
- `SkillManagerModal.tsx`：识别 `metadata.builtin === true` 显示"内置"标签，禁止删除内置 Skill

### P2 高级功能

#### 版本管理
- `V10__skill_version_and_usage.sql`：新建 `skill_version` 表（skill_id, version, content, description, change_note）和 `skill_usage_log` 表
- 新建 `SkillVersionEntity.java`、`SkillVersionRepository.java`
- `SkillDefinitionController.java`：`PUT /api/skills/{id}` 更新 content/description 时自动将当前版本快照写入 `skill_version`；新增 `GET /{id}/versions`、`GET /{id}/versions/{version}`、`POST /{id}/rollback/{version}` 端点
- `SkillEditor.tsx`：编辑模式新增"版本历史"可展开区域，展示版本列表 + 查看历史内容 + 回滚按钮
- `api.ts`：新增 `SkillVersion` 接口、`fetchSkillVersions()`、`fetchSkillVersion()`、`rollbackSkillVersion()` 函数

#### 执行统计
- 新建 `SkillUsageLogEntity.java`、`SkillUsageLogRepository.java`（含 `findUsageStats()` 聚合查询）
- 新建 `SkillUsageRecorder.java` (agent/skills)：SPI 接口，定义 `recordActivation()`
- 新建 `SkillUsageRecorderImpl.java` (gateway/service)：实现 SPI，异步写入 `skill_usage_log`
- `AgentRuntime.java`：注入 `SkillUsageRecorder`，在 `processToolCalls` 中拦截 `readFile`（路径匹配 skills 目录）和 `getSkillContent` 调用，记录 activation
- `SkillDefinitionController.java`：新增 `GET /api/skills/stats`（汇总统计）和 `GET /api/skills/{id}/stats`（单个 Skill 统计）
- `SkillManagerModal.tsx`：加载时获取 stats，每个 Skill 卡片显示激活次数徽标
- `api.ts`：新增 `SkillUsageStats` 接口、`fetchSkillStats()`、`fetchSingleSkillStats()` 函数

### 新增文件清单

| 文件 | 模块 | 说明 |
|------|------|------|
| `V10__skill_version_and_usage.sql` | gateway/migration | skill_version + skill_usage_log 表 |
| `SkillVersionEntity.java` | gateway/entity | 版本实体 |
| `SkillVersionRepository.java` | gateway/repository | 版本 Repository |
| `SkillUsageLogEntity.java` | gateway/entity | 使用统计实体 |
| `SkillUsageLogRepository.java` | gateway/repository | 使用统计 Repository（含聚合查询） |
| `BuiltinSkillsLoader.java` | gateway/service | 启动时加载内置 Skills |
| `SkillUsageRecorderImpl.java` | gateway/service | SkillUsageRecorder SPI 实现 |
| `GetSkillContentTool.java` | agent/tools | 内置工具: 按名称获取 Skill 内容 |
| `SkillUsageRecorder.java` | agent/skills | SPI 接口: 记录 Skill 激活 |
| `skillImporter.ts` | web/lib | SKILL.md 解析/导出工具函数 |
| `builtin-skills/*/SKILL.md` | gateway/resources | 4 个内置 Skills 资源 |

### 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `SkillContentProvider.java` | + readSkillContent() |
| `SkillContentProviderImpl.java` | 实现 readSkillContent()，注入 SkillFileService |
| `ToolAutoConfiguration.java` | 有条件注册 GetSkillContentTool |
| `ToolGroup.java` | + SKILLS 分组 |
| `AgentRuntime.java` | + SkillUsageRecorder 注入 + tool call 拦截 |
| `SkillDefinitionController.java` | + export/zip/versions/rollback/stats 端点 + 版本自动快照 |
| `SkillFileService.java` | + zipSkillDirectory() |
| `api.ts` | + 导出/版本/统计 API 函数 |
| `SkillManagerModal.tsx` | + 导入/导出 UI + 标签过滤 + builtin 标记 + 统计徽标 |
| `SkillsTab.tsx` | + 标签过滤 |
| `SkillEditor.tsx` | + 版本历史 UI |

---

## 2026-03-12 — Skills 阻塞修复 + 文件上传功能

| 类型 | Bugfix / Feature |
|------|------------------|
| 描述 | 修复 Skills Discovery 在响应式线程上调用 `block()` 导致的 `IllegalStateException`，新增 Skills 文件上传/管理功能（scripts/references/assets）。 |

### Bugfix: block() 阻塞异常

**根因**: `SkillContentProviderImpl.resolveSkillSummaries()` 使用 `.collectList().block()` 同步阻塞获取数据库结果，但运行在 `reactor-tcp-nio` 线程上，WebFlux 禁止在响应式线程上调用 `block()`。

**后端改动：**
- `SkillContentProvider.java`（SPI 接口）：返回类型从 `List<SkillSummary>` 改为 `Mono<List<SkillSummary>>`
- `SkillContentProviderImpl.java`：移除 `.block()` 调用，直接返回响应式 `Mono` 链
- `AgentRuntime.java`：`executeAgentLoop` 改为先 `flatMapMany` 异步解析 skills summaries，再同步构建 system prompt。`buildSystemPrompt` 和 `buildSkillsDiscovery` 改为接收预解析好的 `List<SkillSummary>` 参数

### Feature: Skills 文件上传/管理

**后端改动：**
- `SkillFileService.java`：新增 `saveFile()`、`deleteFile()`、`hasSubdir()` 方法
- `SkillDefinitionController.java`：
  - 新增 `GET /api/skills/{id}/files` — 列出 scripts/references/assets 文件
  - 新增 `POST /api/skills/{id}/files/{type}` — 上传文件（multipart）
  - 新增 `DELETE /api/skills/{id}/files/{type}/{filename}` — 删除文件
  - 创建 Skill 时自动写入 SKILL.md 到文件系统
  - 更新 content 时同步写入文件系统
  - 删除 Skill 时清理整个目录
  - 上传/删除文件后自动更新 `has_scripts` / `has_references` 标记

**前端改动：**
- `api.ts`：新增 `SkillFiles` 接口、`fetchSkillFiles` / `uploadSkillFile` / `deleteSkillFile` API 函数
- `SkillEditor.tsx`：编辑模式下新增「文件管理」区域，显示 scripts/references/assets 三个子目录的文件列表，每个区域支持上传和逐个删除文件

---

## 2026-03-12 — Skills P0 功能实现

| 类型 | Feature |
|------|---------|
| 描述 | 完整实现 Skills P0 功能：Skills 定义的 CRUD 管理、Agent 链路 skillsEnabled 扩展、Skills Discovery 三阶段渐进式加载注入到 System Prompt、文件系统管理、前端 Skills 管理弹窗和 Agent 配置 Skills 选择 Tab。 |

### 数据库

- `V9__skill_definition.sql`：新增 `skill_definition` 表（name, display_name, description, content, tags, metadata, has_scripts, has_references, enabled），`agent` 表新增 `skills_enabled` 列

### 后端 — Entity / Repository / Controller

- 新增 `SkillDefinitionEntity.java`：完整映射 skill_definition 表所有字段
- 新增 `SkillDefinitionRepository.java`：`findAllByEnabled`、`findByName`、`findAllByNameIn`
- 新增 `SkillDefinitionController.java`：`/api/skills` 完整 CRUD（含名称唯一性校验、409 冲突检测）

### 后端 — Agent 链路扩展（skillsEnabled 全链路传递）

- `AgentEntity.java`：新增 `skillsEnabled` 字段及 getter/setter
- `ResolvedAgentConfig.java`：record 新增 `skillsEnabled` 分量
- `AgentConfigService.java`：`resolve()` 从 entity 读取并传递 `skillsEnabled`
- `AgentRunRequest.java`：record 新增 `skillsEnabled` 分量
- `AgentController.java`：`updateAgent` 支持 `skillsEnabled`，`entityToDto`/`defaultDto` 返回 `skillsEnabled`
- `MessagePipeline.java`：构建 `AgentRunRequest` 时传递 `resolved.skillsEnabled()`

### 后端 — Skills Discovery SPI + AgentRuntime 注入

- 新增 `SkillContentProvider.java`（intellimate-agent，SPI 接口）：`resolveSkillSummaries()`、`getSkillsBasePath()`、`SkillSummary` record
- 新增 `SkillContentProviderImpl.java`（intellimate-gateway，SPI 实现）：支持 null/full/JSON array 三种 skillsEnabled 规格解析，从 DB 查询启用的 Skill 返回摘要
- `AgentRuntime.java`：注入 `SkillContentProvider`，`buildSystemPrompt` 新增 `skillsEnabled` 参数，新增 `buildSkillsDiscovery()` 方法生成 Discovery 索引（仅注入 name + description + SKILL.md 路径）

### 后端 — 文件系统管理 + 配置

- 新增 `SkillFileService.java`：Skill 目录创建/删除/内容读写/子目录列举（scripts/references/assets）
- `application.yml`：新增 `intellimate.skills.dir` 配置项（默认 `./skills`）

### 前端 — API 层 + 状态管理

- `api.ts`：新增 `SkillDefinition`、`SkillDefinitionCreate` 接口，`fetchSkillDefinitions`/`fetchSkillDefinition`/`createSkillDefinition`/`updateSkillDefinition`/`deleteSkillDefinition` API 函数，`AgentConfig` 新增 `skillsEnabled` 字段
- `agentStore.ts`：新增 `skillsEnabledDraft`、`setSkillsEnabled`、`saveSkillsEnabled`，`fetchConfig`/`resetConfig` 同步处理 skillsEnabled

### 前端 — 组件

- 新增 `SkillsTab.tsx`：Agent 配置中的 Skills 选择面板（全部/无/自定义勾选，显示标签、scripts/references 标记）
- 新增 `SkillEditor.tsx`：Skill 创建/编辑表单（名称、显示名、触发描述、标签、SKILL.md 正文）
- 新增 `SkillManagerModal.tsx`：全局 Skills 管理弹窗（列表/创建/编辑三视图，启用/禁用切换，删除确认）

### 前端 — 集成

- `AgentConfigModal.tsx`：`ContextTab` 扩展增加 `"skills"`，ALL_TABS 新增 "Skills" 标签页，body 渲染 `SkillsTab` 组件，`handleSave` 支持 `saveSkillsEnabled`
- `App.tsx`：引入 `SkillManagerModal`，新增 `skillManagerOpen` 状态，传递 `onOpenSkillManager` 给 Sidebar
- `Sidebar.tsx`：管理区新增「Skills 管理」入口按钮（Sparkles 图标）

---

## 2026-03-12 — Agent 配置弹窗固定高度

| 类型 | Bugfix |
|------|--------|
| 描述 | Agent 配置弹窗切换标签页时窗口大小不一致（内容少的标签页弹窗变矮）。将 `max-h` 改为 `h` 固定高度，弹窗始终保持一致大小。 |

**前端改动：**
- `AgentConfigModal.tsx`：`max-h-[90vh] md:max-h-[85vh]` 改为 `h-[90vh] md:h-[85vh]`，移除 body 容器的 `minHeight: 400px`
- `ToolsTab.tsx`、`McpToolsTab.tsx`、`ModelTab.tsx`：移除 `min-h-[400px]`（固定高度弹窗中 flex-1 自动填满）

---

## 2026-03-12 — fix.md 第 9-12 项修复

| 类型 | Bugfix / Refactor |
|------|-------------------|
| 描述 | 修复 fix.md 第 9-12 项：MCP 工具名不显示、标签页面板高度不一致、Agent 模型管理数据不同步、外部模型管理页面布局不统一。 |

### Fix 9: MCP 工具页面工具名不显示

**前端改动：**
- `McpToolsTab.tsx`：`parseDiscoveredTools()` 兼容后端 `toolsDiscovered` 字段的两种存储格式（字符串数组 `["name", ...]` 和对象数组 `[{name, description}, ...]`），修复工具名渲染为空白的问题

### Fix 10: 标签页面板高度统一

**前端改动：**
- `AgentConfigModal.tsx`：body 容器添加 `minHeight: 400px`
- `ToolsTab.tsx`、`McpToolsTab.tsx`、`ModelTab.tsx`：根容器统一添加 `min-h-[400px]`，确保所有标签页切换时视觉高度一致

### Fix 11: Agent 模型管理与外部模型管理数据同步

**前端改动：**
- `ModelTab.tsx`：提取 `loadProviders()` 为独立函数，以 `currentModel` 为依赖项触发重新拉取，确保切换 Agent 或外部修改模型后数据同步更新

### Fix 12: 外部模型管理页面布局统一

**前端改动：**
- `ModelManagerModal.tsx`：从水平 master-detail 布局（左侧厂商列表 + 右侧详情）改为纵向 `flex-col` 布局（Header + Body），厂商列表改为可展开的卡片式。结构与 `AgentConfigModal`、`ToolManagerModal` 完全一致

---

## 2026-03-12 — fix.md 第 7、8 项修复

| 类型 | Bugfix / Refactor |
|------|-------------------|
| 描述 | 修复 fix.md 第 7 项（移除 Agent 卡片上 SVG 渲染异常的图标）和第 8 项（Agent 配置弹窗重构）。 |

### Fix 7: 移除 Agent 卡片 SVG 图标

**前端改动：**
- `AgentCardGrid.tsx`：移除卡片中的 `Bot` 图标（蓝色圆形容器）和 `Settings2` hover 图标，这两个 lucide-react SVG 图标在部分环境下渲染为 `http://www.w3.org/2000/svg` 文本。卡片改为直接展示 Agent 名称和信息

### Fix 8: Agent 配置弹窗重构

**前端改动：**
- `AgentConfigModal.tsx`：
  - 移除左侧 `w-52` Agent 列表面板（选择功能已由 AgentCardGrid 承担）
  - 移除顶部 `ModelSelector` 下拉框
  - 新增"模型管理"标签页（`ContextTab` 扩展为包含 `"model"`）
  - 弹窗改为 `flex-col` 纵向布局，所有标签页面板统一使用 `flex-1 min-h-0 overflow-y-auto px-6 py-4` 容器
  - "模型管理"tab 不显示保存按钮（选择即保存）
- 新建 `ModelTab.tsx`：模型管理标签页组件
  - 先列出所有已启用的厂商（Provider）卡片
  - 点击厂商展开该厂商下的可用模型列表
  - 点击模型即选中并保存，当前选中模型高亮显示
  - 数据来源复用 `modelStore` 和 `/api/model-providers` API
- `McpToolsTab.tsx`：改为从 `fetchMcpServers()` 获取已配置的 DB 服务列表，按 MCP 服务分组展示其 `toolsDiscovered` 中的工具供选择，不再依赖 live `fetchToolsMetadata()` 加载

---

## 2026-03-12 — fix.md 六项问题批量修复

| 类型 | Bugfix / Enhancement |
|------|----------------------|
| 描述 | 修复 fix.md 中列出的全部 6 个问题，涵盖 MCP 工具显示、对话历史、快捷命令、Agent 配置 UI、工具分页、窗口统一。 |

### Fix 1: Agent 配置中 MCP 工具标签正确显示连接状态

**后端改动：**
- `McpServerController.java`：新增 `POST /api/mcp-servers/reconnect` 端点，批量重连所有已启用的 MCP 服务

**前端改动：**
- `McpToolsTab.tsx`：当 live 工具为空时，检查 DB 中是否有已配置的 MCP 服务。若有，显示"已配置但未连接"提示和"重新连接"按钮
- `api.ts`：新增 `reconnectMcpServers()` API 函数

### Fix 2: 保留对话历史 + /clear 指令

**后端改动：**
- `CommandHandler.java`：新增 `/clear` 命令（清除对话记录），`/clear` 和 `/reset` 响应带 `command` 字段用于前端识别，帮助文本改为中文

**前端改动：**
- `chatStore.ts`：重构为按 agent 名称独立存储消息（`messagesByAgent: Record<string, ChatMessage[]>`），切换 agent 时保留各自历史
- `App.tsx`：切换 agent 时调用 `setCurrentAgent` 替代 `clearMessages`，收到 `/clear` 或 `/reset` 响应时自动清空当前 agent 消息

### Fix 3: 快捷命令优化

**前端改动：**
- `Sidebar.tsx`：快捷命令区域精简为仅保留"帮助"按钮
- `CommandPopup.tsx`：新增 `/clear` 指令，命令描述统一为中文

### Fix 4: Agent 配置卡片化

**前端改动：**
- 新增 `AgentCardGrid.tsx`：卡片网格组件，每个卡片显示 Agent 名称、模型、配置标签（SOUL/USER/AGENTS）
- `App.tsx`：点击"Agent 配置"时切换主内容区域为 Agent 卡片网格，不再弹出 Modal。点击卡片后打开配置 Modal
- `AgentConfigModal.tsx`：新增 `initialAgent` prop，支持从卡片直接定位到指定 Agent

### Fix 5: 工具管理分页

**前端改动：**
- 新增 `Pagination.tsx`：通用分页组件，支持页码导航和省略号折叠
- `CustomToolsPanel.tsx`：自定义工具列表每页 10 条，超出后分页
- `McpServerPanel.tsx`：MCP 服务列表每页 10 条，超出后分页

### Fix 6: 统一窗口大小

**前端改动：**
- `CreateAgentModal.tsx`：从 `max-w-md` 改为 `max-w-5xl max-h-[90vh] md:max-h-[85vh]`，与 AgentConfigModal、ToolManagerModal、ModelManagerModal 保持一致

---

## 2026-03-12 — 修复 OpenAI Compatible 厂商 URL 路径重复导致 404

| 类型 | Bugfix |
|------|--------|
| 描述 | 当用户填写的 baseUrl 已包含版本路径（如 `/v3`）时，Spring AI `OpenAiApi` 默认追加 `/v1/chat/completions` 导致最终路径变成 `/v3/v1/chat/completions`（404）。修复后自动检测 baseUrl 中的版本路径，将 completionsPath 调整为 `/chat/completions`。 |

**后端改造：**
- `ChatModelFactory.java`：
  - `createOpenAiCompatible()` 新增 `hasVersionPath()` 检测，当 baseUrl 含 `/v1`、`/v2`、`/v3` 等路径时，自动设置 `completionsPath("/chat/completions")` 和 `embeddingsPath("/embeddings")`

**测试：**
- 新增 `VolcengineConnectionTest.java` 独立测试类（可直接运行 main 方法验证连通性）

---

## 2026-03-11 — 模型厂商连通性测试增强 & 认证诊断

| 类型 | Enhancement / Bugfix |
|------|----------------------|
| 描述 | 模型厂商"测试连接"从"仅检查 Key 非空"升级为"真实 API 调用验证"，可立即发现 AuthenticationError、URL 不可达、超时等问题。增加 ChatModelFactory 诊断日志（含脱敏 API Key），并对 API Key 做服务端 trim 防御。 |

**后端改造：**
- `ModelProviderController.java`：
  - 注入 `ChatModelFactory`，`test()` 方法改为创建临时 ChatModel 并发送最小 Prompt 验证连通性（15 秒超时）
  - 新增 `extractErrorMessage()` 递归提取根因异常信息
  - `create()` / `update()` 中对 API Key 做 `.trim()` 防御性处理
- `ChatModelFactory.java`：
  - `create()` 入口统一日志输出 provider name / type / baseUrl / maskedApiKey
  - 新增 `maskKey()` 方法（前 4 位 + **** + 后 4 位）

**前端：** 无改动（后端返回的错误信息更具体，前端已能完整展示）

---

## 2026-03-11 — 多厂商模型管理系统 (P0-P4)

| 类型 | Feature |
|------|---------|
| 描述 | 实现完整的多厂商模型管理系统，支持 DashScope / OpenAI Compatible / Anthropic 三种厂商类型。每个 Agent 可独立选择不同厂商的不同模型，模型配置存储在数据库中，支持前端 CRUD 管理。API Key 使用 AES-256-GCM 加密存储。 |

**后端新建：**
- `V7__model_management.sql`：创建 `model_provider` 和 `model_definition` 表
- `V8__seed_default_provider.sql`：插入默认 DashScope 厂商 + 4 个 qwen 模型
- `ModelProviderEntity.java` / `ModelDefinitionEntity.java`：R2DBC 实体
- `ModelProviderRepository.java` / `ModelDefinitionRepository.java`：响应式 Repository
- `CryptoService.java`：AES-256-GCM 加解密 + API Key 脱敏
- `ProviderType.java`：枚举（DASHSCOPE / OPENAI_COMPATIBLE / ANTHROPIC）
- `ProviderConfig.java` / `ModelConfig.java` / `ResolvedModel.java`：不可变配置 records
- `ChatModelFactory.java`：根据厂商类型动态创建 ChatModel（DashScope / OpenAI / Anthropic）
- `ChatModelRegistry.java`：ChatModel 缓存注册表 + resolve by definitionId / modelName
- `ModelRegistryService.java`：DB↔Registry 桥接，ApplicationReadyEvent 启动加载，API Key 自动迁移
- `ModelProviderController.java`：厂商 CRUD + 测试连接 API
- `ModelDefinitionController.java`：模型 CRUD + 按厂商分组列表接口

**后端改造：**
- `AgentRuntime.java`：注入 `ChatModelRegistry` 替换直接的 `ChatModel`，per-request 模型解析 + `ToolCallingChatOptions.model()` 设置
- `IntelliMateApplication.java`：exclude `OpenAiAutoConfiguration` 和 `AnthropicAutoConfiguration`
- `IntelliMateProperties.java`：Security 新增 `cryptoKey` 字段
- `application.yml`：新增 `crypto-key` 配置项
- `pom.xml`（agent + gateway）：新增 `spring-ai-starter-model-openai` 和 `spring-ai-starter-model-anthropic` 依赖

**前端新建：**
- `ModelSelector.tsx`：分组下拉选择器组件（按厂商分组显示可用模型）
- `ModelManagerModal.tsx`：模型管理弹窗（master-detail 布局：左侧厂商列表 + 右侧配置面板）
- `ProviderEditor.tsx`：厂商配置表单（名称、类型、API 地址、API Key 脱敏输入、测试连接）
- `ModelList.tsx`：模型列表行内 CRUD
- `modelStore.ts`：Zustand store 管理厂商/模型列表和 CRUD 操作

**前端改造：**
- `api.ts`：新增 `ModelGroup`、`ModelItem`、`ModelProviderDto`、`ModelDefinitionDto` 等类型 + 全部 CRUD API 函数
- `AgentConfigModal.tsx`：移除硬编码 `MODELS` 数组，header 区域集成 `ModelSelector`，模型变更立即保存
- `CreateAgentModal.tsx`：移除硬编码 `MODELS`，使用 `ModelSelector` 组件
- `agentStore.ts`：新增 `saveModel()` action
- `Sidebar.tsx`：管理区域新增"模型管理"入口
- `App.tsx`：新增 `modelManagerOpen` 状态 + `ModelManagerModal` 渲染

---

## 2026-03-11 — 项目初始化

| 类型 | Feature |
|------|---------|
| 描述 | 搭建 IntelliMate 多模块项目骨架，技术栈为 Spring Boot 3.4.3 + WebFlux + R2DBC + Flyway + Spring AI Alibaba（DashScope） |

**后端改动：**
- 创建 Maven 父 POM + 4 个子模块：`intellimate-core`、`intellimate-channel-api`、`intellimate-agent`、`intellimate-gateway`
- `intellimate-core`：`IntelliMateProperties`、`SessionKey`、`SessionMetadata`、WebSocket 协议帧（`GatewayFrame`、`EventFrame`、`RequestFrame`、`ResponseFrame`）
- `intellimate-channel-api`：Channel SPI 接口
- `intellimate-agent`：`AgentRuntime`、`RunQueueManager`、`AgentRunRequest`、`ToolsEngine`、6 个工具类（`ExecTool`、`FileReadTool`、`FileWriteTool`、`FileEditTool`、`WebSearchTool`、`WebFetchTool`）
- `intellimate-gateway`：`IntelliMateApplication`、`WebSocketHandler`、`MessagePipeline`、`CommandHandler`、`SessionManager`、`AgentConfigService`、`AuditService`，Flyway 迁移脚本 `V1__init_schema.sql`（7 张表）
- `application.yml` 全套配置（R2DBC、Flyway、DashScope、CORS）

**前端改动：** 无

---

## 2026-03-11 — Bug 修复：HealthEndpoint Bean 冲突

| 类型 | Fix |
|------|-----|
| 描述 | 自定义 `HealthEndpoint` 与 Spring Boot Actuator 同名 Bean 冲突导致启动失败 |

**后端改动：**
- 删除 `intellimate-gateway/.../http/HealthEndpoint.java`
- 修改 `application.yml` 配置 Actuator 端点暴露

**前端改动：** 无

---

## 2026-03-11 — Bug 修复：DashScope AutoConfiguration 缺少 RestClient.Builder

| 类型 | Fix |
|------|-----|
| 描述 | DashScope 多个自动配置类（Agent、Embedding、Image 等）依赖 Servlet 栈的 `RestClient.Builder`，在 WebFlux 环境下启动失败 |

**后端改动：**
- 修改 `IntelliMateApplication.java`：`@SpringBootApplication(exclude = {...})` 排除 6 个非 Chat 的 DashScope 自动配置类

**前端改动：** 无

---

## 2026-03-11 — Bug 修复：Flyway 不执行 SQL 迁移

| 类型 | Fix |
|------|-----|
| 描述 | R2DBC 环境下 Flyway 静默跳过迁移，数据库表未创建 |

**后端改动：**
- 修改 `application.yml`：改用 `spring.flyway.url/user/password` 独立 JDBC 连接
- 修改 `intellimate-gateway/pom.xml`：移除 `spring-boot-starter-jdbc`，保留 `mysql-connector-j`

**前端改动：** 无

---

## 2026-03-11 — 6 轮功能实现

| 类型 | Feature |
|------|---------|
| 描述 | 实现核心功能：流式响应、斜杠命令（/help /clear /approve）、WebSocket 心跳、渠道管理、DB Agent 配置与工具过滤、白名单与 DM 配对、Web 搜索与审计日志 |

**后端改动：**
- `AgentRuntime`：`ChatClient.prompt().stream().content()` 流式 LLM 调用
- `CommandHandler`：处理 `/help`、`/clear`、`/approve` 等命令，返回 `ResponseFrame`
- `WebSocketHandler`：ping/pong 心跳、token 认证、session.welcome 事件
- `ChannelConfig` / `AllowlistEntry` / `PairingRequest`：渠道管理和安全相关实体与 Repository
- `AgentConfigService`：DB 优先 + yml 回退的 Agent 配置解析
- `ToolProfile` 枚举：FULL / CODING / MESSAGING / MINIMAL 预设
- `ToolsEngine.getToolCallbacksFor()`：按 profile 或 JSON 数组过滤工具
- `AuditService`：审计日志持久化

**前端改动：** 无（此时前端尚未创建）

---

## 2026-03-11 — 前端项目创建与 UI 实现

| 类型 | Feature |
|------|---------|
| 描述 | 创建 `intellimate-web` 前端项目（React 19 + TypeScript + Vite 6 + Tailwind CSS 4 + Zustand），实现完整聊天界面 |

**后端改动：**
- `WebSocketHandler`：增加 CORS 配置允许前端开发服务器连接

**前端改动：**
- 新建 `intellimate-web/` 项目，`package.json` + `vite.config.ts` + `tailwind.config.ts`
- `WsClient`：WebSocket 客户端，自动重连、心跳、token 认证
- `protocol.ts`：`RequestFrame` / `ResponseFrame` / `EventFrame` 协议定义
- `chatStore.ts`（Zustand）：消息状态管理，流式追加
- `useWebSocket.ts`：React Hook，事件分发
- UI 组件：`App`、`Sidebar`、`TopBar`、`ChatPanel`、`MessageList`、`MessageBubble`、`ComposeArea`、`StreamingText`、`ConnectionStatus`、`CommandPopup`

---

## 2026-03-11 — Bug 修复：ChatClient.tools() 与 ToolCallback 类型不兼容

| 类型 | Fix |
|------|-----|
| 描述 | `AgentRuntime` 调用 `.tools()` 传入 `ToolCallback[]`，Spring AI 期望原始 Bean 而非已包装的回调 |

**后端改动：**
- 修改 `AgentRuntime.java`：`.tools(...)` → `.toolCallbacks(...)`

**前端改动：** 无

---

## 2026-03-11 — Bug 修复：前端命令响应卡在"思考中"

| 类型 | Fix |
|------|-----|
| 描述 | 发送 `/help` 等命令后，前端显示"思考中..."不更新。原因是命令直接返回 `ResponseFrame`，但前端只处理错误响应和流式事件 |

**后端改动：** 无

**前端改动：**
- 修改 `chatStore.ts`：`addResponse()` 支持处理成功的 `ResponseFrame`，填充 `payload.text`
- 修改 `useWebSocket.ts`：增加 `isWaiting` 状态和超时保护
- 修改 `ComposeArea.tsx`：输入锁定防止重复发送

---

## 2026-03-11 — 完善前后端模型调用和回复

| 类型 | Feature |
|------|---------|
| 描述 | 完善端到端的 LLM 流式调用链路，确保命令响应和流式输出在前端正确渲染 |

**后端改动：** 无

**前端改动：**
- 修改 `chatStore.ts`：`addResponse()` 处理直接响应和流式完成两种场景
- 修改 `useWebSocket.ts`：`REQUEST_TIMEOUT_MS` 超时机制
- 修改 `ComposeArea.tsx`：`isWaiting` 输入锁定

---

## 2026-03-11 — LLM 请求参数 JSON 日志

| 类型 | Feature |
|------|---------|
| 描述 | 在调用 LLM 前将请求参数（session、model、systemPrompt、history、tools 等）以 JSON 格式打印到 DEBUG 日志 |

**后端改动：**
- 修改 `AgentRuntime.java`：新增 `logRequestParams()` 方法，使用 `ObjectMapper.writerWithDefaultPrettyPrinter()` 格式化输出

**前端改动：** 无

---

## 2026-03-11 — Bug 修复：前端聊天记录多时整页滚动

| 类型 | Fix |
|------|-----|
| 描述 | 聊天记录多时整个页面滚动而非仅对话窗口滚动，原因是 Flexbox 容器缺少 `min-h-0` 约束 |

**后端改动：** 无

**前端改动：**
- 修改 `App.tsx`：外层 `div` 添加 `min-h-0`
- 修改 `ChatPanel.tsx`：根 `div` 添加 `min-h-0`

---

## 2026-03-11 — Bug 修复：AgentController GET 接口 404

| 类型 | Fix |
|------|-----|
| 描述 | `GET /api/agent/intellimate` 返回 404，因为 DB 中无 agent 记录但 Controller 未做回退 |

**后端改动：**
- 修改 `AgentController.java`：`getAgent()` 使用 `.defaultIfEmpty()` 回退到 yml 默认值；`updateContext()` 实现 upsert（无记录时自动创建）

**前端改动：** 无

---

## 2026-03-11 — 多 Agent 智能体系统

| 类型 | Feature |
|------|---------|
| 描述 | 支持多个独立配置的 Agent（各自拥有 SOUL/USER/AGENTS 上下文、model、tools），前端可切换和管理 |

**后端改动：**
- `AgentEntity`：增加 `soulMd`、`userMd`、`agentsMd` 字段
- `AgentRepository`：增加 `findAllActive()`、`updateContextByName()`、`softDeleteByName()` 查询
- `AgentController`：CRUD API（`GET /api/agents`、`POST /api/agent`、`PUT /api/agent/{name}`、`PUT /api/agent/{name}/context`、`DELETE /api/agent/{name}`）
- `AgentConfigService`：resolve() 方法，DB 优先 + yml 回退
- `MessagePipeline`：`contextId = baseContextId + "::" + agentName`，按 agent 隔离会话
- Flyway 迁移：`V2__add_agent_context_fields.sql`

**前端改动：**
- 新建 `agentStore.ts`（Zustand）：agent 列表、选择、创建、删除、配置编辑
- 新建 `api.ts`：REST API 封装（`fetchAgents`、`fetchAgentConfig`、`createAgentApi`、`updateAgentContext`、`deleteAgentApi`）
- 新建 `AgentConfigModal.tsx`：SOUL/USER/AGENTS 三标签编辑面板
- 新建 `AgentContextEditor.tsx`：Markdown 文本编辑器
- 修改 `Sidebar.tsx`：Agent 列表、选择、创建、删除交互
- 修改 `useWebSocket.ts`：发送消息时携带 `agentName`

---

## 2026-03-11 — Agent Loop 多轮自主执行

| 类型 | Feature |
|------|---------|
| 描述 | 从单轮一问一答改造为多轮自主 Agent Loop。LLM 决定是否调用工具，工具调用/结果实时推送到前端。使用 `ChatModel.stream()` + `internalToolExecutionEnabled(false)` 手动控制工具执行 |

**后端改动：**
- 新建 `AgentEvent.java`：sealed interface，6 种事件（`TurnStart`、`TextChunk`、`ToolCall`、`ToolResult`、`Done`、`Error`）
- 重写 `AgentRuntime.java`：注入 `ChatModel` 替代 `ChatClient`，`dispatch()` 返回 `Flux<AgentEvent>`，实现 `executeAgentLoop()` + `executeLoopTurn()` 递归循环，`processToolCalls()` 手动执行工具
- 修改 `RunQueueManager.java`：泛型 `Flux<String>` → `Flux<AgentEvent>`
- 修改 `ToolsEngine.java`：新增 `getCallbackByName()` 方法
- 修改 `MessagePipeline.java`：消费 `Flux<AgentEvent>`，新增 `mapAgentEvent()` 映射方法（`agent.turn_start`、`agent.tool_call`、`agent.tool_result` 事件），`convertToAiMessages()` 支持 `tool` role

**前端改动：**
- 修改 `chatStore.ts`：新增 `ToolCallInfo` 接口、`ChatMessage` 扩展 `toolCalls` / `currentTurn` / `maxTurns` / `totalTurns`，新增 `setTurnStart()` / `addToolCall()` / `updateToolResult()` actions
- 修改 `useWebSocket.ts`：新增 `agent.turn_start` / `agent.tool_call` / `agent.tool_result` 事件处理，超时延长至 300s
- 新建 `ToolCallCard.tsx`：工具调用卡片（状态动画 + 可折叠参数/结果）
- 修改 `MessageBubble.tsx`：嵌入 `ToolCallCard` 列表、Turn 指示器、"共 N 轮推理"标签

---

## 2026-03-11 — 工具管理 UI

| 类型 | Feature |
|------|---------|
| 描述 | 参照 OpenClaw 的工具管理机制，为 IntelliMate 增加工具分组、工具列表 API 和前端工具配置 UI。在 AgentConfigModal 中新增"工具"标签页，支持 Profile 快速选择和单个工具开关 |

**后端改动：**
- 新建 `ToolGroup.java`：枚举定义 FS / RUNTIME / WEB 三个工具分组
- 修改 `ToolsEngine.java`：新增 `getToolMetadata()` 返回工具元信息（name、description、group）
- 新建 `ToolController.java`：`GET /api/tools` 返回工具列表、Profile 预设、分组信息
- 修改 `AgentController.java`：`entityToDto()` / `entityToSummaryDto()` / `defaultDto()` 暴露 `toolsEnabled`；`PUT /api/agent/{name}` 支持写入 `toolsEnabled`

**前端改动：**
- 修改 `api.ts`：新增 `ToolInfo`、`ToolProfileInfo`、`ToolGroupInfo`、`ToolsMetadata` 类型，`AgentConfig` 增加 `toolsEnabled`，新增 `fetchToolsMetadata()`
- 修改 `agentStore.ts`：新增 `toolsEnabledDraft` 状态、`setToolsEnabled()` / `saveToolsEnabled()` 方法
- 新建 `ToolsTab.tsx`：Profile 选择器 + 按分组展示的工具开关列表 + 当前配置值展示
- 修改 `AgentConfigModal.tsx`：重构为双层标签（"上下文" / "工具"），上下文内保持 SOUL/USER/AGENTS 子标签

---

## 2026-03-11 — 工具设置独立面板 & 性格设置重命名

| 类型 | Refactor |
|------|----------|
| 描述 | 将工具设置从 AgentConfigModal 中拆分为独立的 ToolsModal，Sidebar 新增"工具设置"区域入口；原"智能体配置"重命名为"性格设置" |

**后端改动：** 无

**前端改动：**
- 新建 `ToolsModal.tsx`：独立的工具配置弹窗，内嵌 `ToolsTab` 组件，具备加载、保存、错误处理等完整交互
- 修改 `AgentConfigModal.tsx`：移除"上下文/工具"双层标签，回归仅包含 SOUL/USER/AGENTS 上下文编辑；标题从"智能体配置"改为"性格设置"
- 修改 `Sidebar.tsx`："智能体配置"区域标题改为"性格设置"；新增"工具设置"区域，包含"管理工具列表"入口按钮（Wrench 图标）
- 修改 `App.tsx`：新增 `toolsModalOpen` 状态和 `ToolsModal` 渲染，Sidebar 传入 `onOpenTools` 回调

---

## 2026-03-11 — Bug 修复：工具设置保存 500 & 弹窗调大

| 类型 | Fix |
|------|-----|
| 描述 | 保存工具设置时报 500 `Invalid JSON text`，原因是 `tools_enabled` 列为 MySQL `JSON` 类型，但 profile 名称（如 `coding`、`minimal`）不是合法 JSON；同时 `updateAgent` 未处理 agent 不存在的情况；工具设置弹窗过小 |

**后端改动：**
- 新增 Flyway 迁移 `V3__tools_enabled_to_text.sql`：将 `agent.tools_enabled` 列从 `JSON` 改为 `TEXT`
- 修改 `AgentController.java`：`updateAgent()` 方法在 agent 不存在时自动创建记录（`createDefaultEntity()`），与 `updateContext` 行为一致

**前端改动：**
- 修改 `ToolsModal.tsx`：弹窗最大宽度从 `max-w-lg` 调大为 `max-w-2xl`，最大高度从 `80vh` 调为 `85vh`

---

## 2026-03-11 — P0 自定义 HTTP 工具 + 前端 CRUD

| 类型 | Feature |
|------|---------|
| 描述 | 实现工具动态配置 P0 阶段：在前端创建/编辑/删除/测试自定义 HTTP API 工具，后端 DB 存储、动态加载，Agent 调用时自动合并内置与自定义工具 |

**后端改动：**
- 新增 Flyway 迁移 `V4__dynamic_tools.sql`：创建 `tool_definition` 表（name、type、description、parameters_schema、execution_config、timeout_seconds、group_name、agent_name、enabled）
- 新建 `ToolDefinitionEntity.java`：对应 `tool_definition` 表
- 新建 `ToolDefinitionRepository.java`：`findAllByEnabled()`、`findByName()`
- 新建 `intellimate-agent/tools/dynamic/` 包：
  - `HttpExecutionConfig` record：HTTP 调用配置（url、method、headers、bodyTemplate、responseExtract）
  - `DynamicToolDefinition` record：便携式工具定义，与 Entity 解耦
  - `HttpToolCallback`：实现 `ToolCallback` 接口，解析 JSON 参数 → 模板变量替换（`${param}`、`${env:VAR}`） → WebClient HTTP 调用 → JSONPath 响应提取
  - `DynamicToolProvider` 接口：SPI，定义 `getDynamicCallbacks()` / `reload()`
- 新建 `DynamicToolProviderImpl.java`（gateway/service）：实现 `DynamicToolProvider`，从 DB 加载 enabled 的 HTTP_API 工具并构建 `HttpToolCallback`
- 重构 `ToolsEngine.java`：
  - 区分 `builtinCallbacks` 与动态工具，通过 `@Autowired(required = false)` 注入 `DynamicToolProvider`
  - 新增 `refresh()` 方法：合并内置 + 动态工具重建注册表
  - 新增 `getAllGroups()` 方法：合并枚举分组 + CUSTOM 动态分组
  - `getToolMetadata()` 增加 `source` 字段（builtin / custom）
- 新建 `ToolDefinitionController.java`：
  - `GET /api/tool-definitions`：列出所有自定义工具
  - `POST /api/tool-definitions`：创建（含名称正则校验 `^[a-zA-Z][a-zA-Z0-9_]{1,63}$`、唯一性检查）
  - `PUT /api/tool-definitions/{id}`：更新
  - `DELETE /api/tool-definitions/{id}`：删除
  - `POST /api/tool-definitions/{id}/test`：测试执行（传入参数 → 调用 HttpToolCallback → 返回结果/耗时）
  - 每个 CUD 操作完成后调用 `dynamicToolProvider.reload()` + `toolsEngine.refresh()` 热重载
- 改造 `ToolController.java`：`groups` 改用 `toolsEngine.getAllGroups()`
- `intellimate-agent/pom.xml` 新增 `spring-boot-starter-webflux`（WebClient）和 `json-path`（JSONPath 提取）

**前端改动：**
- 修改 `api.ts`：新增 `ToolDefinition`、`ToolDefinitionCreate`、`ToolTestRequest`、`ToolTestResult` 接口；新增 `fetchToolDefinitions()`、`createToolDefinition()`、`updateToolDefinition()`、`deleteToolDefinition()`、`testToolDefinition()` 函数；`ToolInfo` 增加 `source` 字段
- 新建 `toolStore.ts`（Zustand）：`definitions` 列表、CRUD actions、`testDefinition` action
- 改造 `ToolsModal.tsx`：新增「内置工具」/「自定义工具」双 Tab 切换，宽度增大至 `max-w-3xl`
- 新建 `CustomToolsPanel.tsx`：自定义工具列表（创建/编辑/删除/启用禁用切换）
- 新建 `CustomToolEditor.tsx`：创建/编辑表单（基本信息、HTTP 配置、参数定义、测试执行面板）

---

## 2026-03-11 — P1 MCP Server 集成

| 类型 | Feature |
|------|---------|
| 描述 | 实现工具动态配置 P1 阶段：前端配置 MCP Server 连接，IntelliMate 作为 MCP Client 自动发现并注册远程工具。支持 SSE / STDIO 传输类型，工具名前缀隔离 |

**后端改动：**
- 新增 Flyway 迁移 `V5__mcp_server.sql`：创建 `mcp_server` 表（name、server_url、transport_type、auth_config、agent_name、enabled、last_connected_at、tools_discovered）
- 新建 `McpServerEntity.java`：对应 `mcp_server` 表
- 新建 `McpServerRepository.java`：`findAllByEnabled()`、`findByName()`
- `intellimate-gateway/pom.xml` 新增 `spring-ai-starter-mcp-client-webflux` 依赖
- `application.yml` 新增 `spring.ai.mcp.client.enabled: false` 禁用 MCP 自动配置（程序化管理客户端）
- 新建 `intellimate-agent/tools/mcp/` 包：
  - `McpToolProvider` 接口：定义 `getAllCallbacks()` / `getServerToolNames()`
  - `PrefixedToolCallback`：包装 `ToolCallback`，为工具名添加 `mcp_{serverName}_` 前缀避免冲突
- 新建 `McpToolProviderImpl.java`（gateway/service）：
  - 管理 `McpSyncClient` 连接池（ConcurrentHashMap）
  - `connectServerSync()`：创建 MCP Client → initialize → SyncMcpToolCallbackProvider 获取工具 → 前缀包装 → 缓存
  - `disconnectServer()`：关闭连接并清除缓存
  - `testConnection()`：临时连接 → listTools → 关闭，返回发现的工具列表
  - 支持 SSE（WebFluxSseClientTransport）和 STDIO（StdioClientTransport + ServerParameters）两种传输
  - 启动时通过 `@EventListener(ApplicationReadyEvent.class)` 自动连接所有 enabled 的 MCP Server
- 重构 `ToolsEngine.java`：
  - 构造函数新增 `@Autowired(required = false) McpToolProvider mcpToolProvider`
  - `refresh()` 合并 builtin + dynamic + MCP 三种来源
  - `getAllGroups()` 追加每个 MCP Server 独立分组 `"MCP:{serverName}"`
  - `detectSource()` 识别 `mcp_` 前缀返回 `"mcp"` 来源
  - `getToolMetadata()` 中 MCP 工具对应独立的 group/groupDisplayName
- 新建 `McpServerController.java`：
  - `GET /api/mcp-servers`：列出所有 MCP Server
  - `POST /api/mcp-servers`：添加（含名称正则校验、唯一性检查），成功后 connectServer + refresh
  - `PUT /api/mcp-servers/{id}`：更新，disconnect 旧连接 → 重新 connect
  - `DELETE /api/mcp-servers/{id}`：disconnectServer + 删除 DB 记录 + refresh
  - `POST /api/mcp-servers/{id}/test`：测试连接并返回发现的工具列表
  - 每个 CUD 操作完成后调用 `toolsEngine.refresh()` 热重载

**前端改动：**
- 修改 `api.ts`：新增 `McpServer`、`McpServerCreate`、`McpDiscoveredTool`、`McpTestResult` 接口；新增 `fetchMcpServers()`、`createMcpServer()`、`updateMcpServer()`、`deleteMcpServer()`、`testMcpServer()` 函数
- 修改 `toolStore.ts`：新增 `mcpServers` 列表、`mcpLoading` / `mcpError` 状态；新增 MCP CRUD + test actions
- 改造 `ToolsModal.tsx`：新增第三个 Tab「MCP 服务」
- 新建 `McpServerPanel.tsx`：MCP Server 列表（名称、URL、传输类型、工具数、最后连接时间、启停/测试/编辑/删除按钮），空状态提示
- 新建 `McpServerEditor.tsx`：添加/编辑 MCP Server 表单（名称、传输类型选择 SSE/STDIO、URL/JSON 配置、测试连接按钮 → 显示发现的工具列表）

---

## 2026-03-11 — Streamable HTTP 传输支持 & MCP 连接修复

| 类型 | Feature / Fix |
|------|---------------|
| 描述 | MCP 新增 Streamable HTTP 传输类型支持；修复 Jina MCP 连接 404（URL 拼接错误）；升级 MCP SDK 至 0.11.3；优化连接失败时的错误处理（保存配置 → 异步尝试连接 → 失败置断开状态）；工具名正则允许 `-`；新增 `POST /api/mcp-servers/test-config` 支持保存前测试 |

**后端改动：**
- `pom.xml`：新增 `<mcp-sdk.version>0.11.3</mcp-sdk.version>`，依赖管理覆盖 Spring AI 内置版本
- `McpToolProviderImpl.java`：`createSyncClient()` 新增 `STREAMABLE_HTTP` case，正确拆分 URL（scheme+authority → baseUrl，path → endpoint）
- `McpServerController.java`：`create` / `update` 改为先保存 → 异步连接 → 失败时置断开状态；新增 `POST /api/mcp-servers/test-config`
- `ToolDefinitionController.java`：工具名正则更新为 `^[a-zA-Z][a-zA-Z0-9_-]{1,63}$`，错误信息改为用户友好描述

**前端改动：**
- `McpServerEditor.tsx`：`TRANSPORT_OPTIONS` 新增 `STREAMABLE_HTTP`（设为推荐默认），更新 URL placeholder 和 helper text

---

## 2026-03-11 — UI 重构：Agent 配置与工具管理分离

| 类型 | Refactor |
|------|----------|
| 描述 | 将 UI 拆分为两大模块：(1) AgentConfigModal 合并性格设置 + 工具选择（标题改为"Agent 配置"）(2) 新建 ToolManagerModal 全局管理自定义工具和 MCP 服务；删除 ToolsModal；侧边栏重构为 Agent 配置区 + 工具管理区；后端增强工具传递 INFO 日志 |

**后端改动：**
- `ToolsEngine.java`：新增 `getBuiltinCount()` / `getDynamicCount()` / `getMcpCount()` 方法
- `AgentRuntime.java`：`executeAgentLoop()` 新增 INFO 日志 `"Agent '{}' tools: {} total ({} builtin, {} custom, {} mcp), spec='{}'""`

**前端改动：**
- 改造 `AgentConfigModal.tsx`：标题从"性格设置"改为"Agent 配置"；`ContextTab` 类型扩展为 `"soul" | "user" | "agents" | "tools"`；新增"工具选择"标签页渲染 `ToolsTab`；保存按钮区分性格 tab 调 `saveConfig()` 工具 tab 调 `saveToolsEnabled()`
- 新建 `ToolManagerModal.tsx`：全局工具管理弹窗，两个 tab（自定义工具 / MCP 服务），复用 `CustomToolsPanel` 和 `McpServerPanel`
- 删除 `ToolsModal.tsx`：功能已拆分到 AgentConfigModal 和 ToolManagerModal
- 改造 `Sidebar.tsx`：移除 `onOpenTools`，新增 `onOpenToolManager`；侧边栏新增 "Agent 配置" 区（SOUL/USER/AGENTS/工具选择 4 个入口）和 "工具管理" 区（自定义工具/MCP 服务入口）
- 改造 `App.tsx`：移除 `toolsModalOpen` / `ToolsModal`，新增 `toolManagerOpen` / `ToolManagerModal`，Sidebar props 替换为 `onOpenToolManager`

---

## 2026-03-11 — Agent 管理页面重构

| 类型 | Refactor |
|------|----------|
| 描述 | AgentConfigModal 重构为 master-detail 布局：左侧 Agent 列表（选择/新建/删除），右侧配置面板（SOUL/USER/AGENTS/工具选择 4 个 tab）。侧边栏"Agent 配置"从 4 个子入口简化为单个"Agent 配置"按钮，"Agent 配置"和"工具管理"合并为"管理"区块。AgentConfigModal 不再依赖外部传入的 agentName，内部管理选中 Agent 状态 |

**后端改动：** 无

**前端改动：**
- 重构 `AgentConfigModal.tsx`：移除 `agentName` prop，新增左侧 Agent 列表面板（内嵌创建表单 + 删除按钮）；内部维护 `selectedAgent` 状态，切换 Agent 时自动加载配置并检测未保存修改；弹窗宽度从 `max-w-3xl` 增大为 `max-w-5xl`
- 重构 `Sidebar.tsx`：移除 `onOpenConfig(tab)` prop 和 4 个 Agent 配置子入口按钮，新增 `onOpenAgentManager` prop 和单个"Agent 配置"入口；"Agent 配置"和"工具管理"合并为"管理"区块
- 重构 `App.tsx`：`configModal` state 简化为 `agentManagerOpen` boolean；`AgentConfigModal` 不再传入 `agentName` 和 `initialTab`；Sidebar props 中 `onOpenConfig` 替换为 `onOpenAgentManager`

---

## 2026-03-11 — Agent 级 MCP 工具选择

| 类型 | Feature |
|------|---------|
| 描述 | 为每个 Agent 新增独立的 MCP 工具选择能力。`tools_enabled` 控制内置 + 自定义工具，新增 `mcp_tools_enabled` 字段控制 MCP 工具（null=不启用, "full"=全部, JSON数组=指定工具名）。前端 AgentConfigModal 新增"MCP 工具"标签页，ToolManagerModal 尺寸调整与 AgentConfigModal 一致 |

**后端改动：**
- 新增 Flyway 迁移 `V6__agent_mcp_tools.sql`：`agent` 表新增 `mcp_tools_enabled` TEXT 列
- `AgentEntity.java`：新增 `mcpToolsEnabled` 字段 + getter/setter
- `AgentController.java`：`entityToDto()` / `defaultDto()` 暴露 `mcpToolsEnabled`；`updateAgent()` 支持写入 `mcpToolsEnabled`
- `ResolvedAgentConfig.java`：record 新增 `mcpToolsEnabled` 参数
- `AgentConfigService.java`：`resolve()` 传递 `entity.getMcpToolsEnabled()`
- `AgentRunRequest.java`：record 新增 `mcpToolsEnabled` 参数
- `MessagePipeline.java`：构造 `AgentRunRequest` 时传入 `resolved.mcpToolsEnabled()`
- `ToolsEngine.java`：新增 `getToolCallbacksFor(String toolsEnabledSpec, String mcpToolsEnabledSpec)` 双参数方法，内部拆分为 `getBuiltinCustomCallbacksFor()` 和 `getMcpCallbacksFor()` 独立过滤逻辑
- `AgentRuntime.java`：改用双参数方法 `getToolCallbacksFor(toolsEnabled, mcpToolsEnabled)`，INFO 日志新增 `mcpSpec`

**前端改动：**
- `api.ts`：`AgentConfig` 新增 `mcpToolsEnabled: string | null`
- `agentStore.ts`：新增 `mcpToolsEnabledDraft` 状态、`setMcpToolsEnabled()` / `saveMcpToolsEnabled()` actions
- 新建 `McpToolsTab.tsx`：MCP 工具选择面板（快速选择"全部/无"、按 MCP 服务器分组展示工具列表、单独勾选）
- `AgentConfigModal.tsx`：`ContextTab` 扩展为包含 `"mcp"`，`ALL_TABS` 新增 `{ key: "mcp", label: "MCP 工具" }`，渲染 `McpToolsTab`，`handleSave` 新增 `mcp` 分支调用 `saveMcpToolsEnabled()`
- `ToolManagerModal.tsx`：弹窗最大宽度从 `max-w-3xl` 调大为 `max-w-5xl`

---

## 设计文档记录

以下设计文档在开发过程中创建，不涉及代码改动：

| 时间 | 文档 | 说明 |
|------|------|------|
| 2026-03-11 | `doc/target/requirements.md` | 需求文档 |
| 2026-03-11 | `doc/target/architecture.md` | 架构设计 |
| 2026-03-11 | `doc/target/task-breakdown.md` | 任务分解 |
| 2026-03-11 | `doc/ui-designer.md` | 前端 UI 设计 |
| 2026-03-11 | `doc/process/troubleshooting.md` | 启动问题排查记录（6 个问题） |
| 2026-03-11 | `doc/process/feature-agent-context-files.md` | Agent 上下文文件功能分析 |
| 2026-03-11 | `doc/process/design-multi-agent.md` | 多 Agent 产品设计 |
| 2026-03-11 | `doc/process/tech-multi-agent.md` | 多 Agent 技术设计 |
| 2026-03-11 | `doc/process/model-execution-flow.md` | 模型执行流程记录 |
| 2026-03-11 | `doc/process/tech-agent-loop.md` | Agent Loop 技术设计 |
| 2026-03-11 | `doc/process/tool/OpenClaw-tools-designer.md` | OpenClaw 工具管理分析 |
| 2026-03-11 | `doc/process/tool/IntelliMate_tools-designer.md` | IntelliMate 工具管理设计 |
| 2026-03-11 | `doc/process/tool/工具动态配置_设计方案.md` | 工具动态配置产品设计 |
| 2026-03-11 | `doc/process/tool/工具动态配置_技术方案.md` | 工具动态配置技术方案 |
