# Embedding 模型解耦设计规格 — 从硬编码到页面可配置

## 背景

记忆系统 v2 引入了 Qdrant 向量检索，Embedding 模型在 `QdrantVectorStoreConfig` 中硬编码绑定了阿里 DashScope：

```java
// QdrantVectorStoreConfig.java — 当前实现
DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(apiKey).build();
return new DashScopeEmbeddingModel(dashScopeApi, MetadataMode.EMBED, options);
```

问题：
- **厂商锁定**：Embedding 模型固定为 DashScope `text-embedding-v3`，无法使用 OpenAI、通义千问以外的 embedding 服务
- **配置不一致**：Chat 模型已支持页面配置（`model_provider` + `model_definition` 表），Embedding 仍靠 `application.yml` 硬编码
- **切换成本高**：更换 Embedding 模型需要改代码、改配置、重新编译部署

## 目标

1. 在现有模型管理页面中支持添加 Embedding 类型的模型定义
2. 在记忆配置页面通过下拉选择器指定使用哪个 Embedding 模型
3. 支持运行时热切换（通过 `DelegatingEmbeddingModel` 代理）
4. **彻底移除 `application.yml` 中的 Embedding 硬编码配置**，所有 Embedding 配置仅通过页面管理
5. 未配置 Embedding 模型时，向量功能自动禁用（降级到纯关键词检索）

## 技术选型

| 组件 | 方案 | 理由 |
|------|------|------|
| 数据存储 | 复用 `model_provider` + `model_definition` 表 | 避免新建表，复用现有 Provider 凭证（API Key、Base URL） |
| 热切换 | `DelegatingEmbeddingModel` 代理模式 | `VectorStore` 持有稳定 Bean 引用，内部 delegate 原子替换 |
| 支持的 Provider | DASHSCOPE + OPENAI_COMPATIBLE | 覆盖主流 Embedding 服务；Anthropic/DeepSeek 无原生 Embedding API |
| 前端 | 扩展现有模型管理 + 记忆配置页 | 最小化 UI 改动，用户体验统一 |

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                       intellimate-web                           │
│  ┌──────────────────────┐  ┌──────────────────────────────┐     │
│  │  ModelManagerPage    │  │  MemoryManagerPage            │     │
│  │  + category 选择器   │  │  + Embedding 模型下拉选择器  │     │
│  │  + dimensions 输入   │  │  + 维度变更警告              │     │
│  └──────────┬───────────┘  └──────────────┬───────────────┘     │
└─────────────┼─────────────────────────────┼─────────────────────┘
              │ CRUD                         │ activate
              ▼                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     intellimate-gateway                          │
│                                                                  │
│  ModelDefinitionController     MemoryController                  │
│  + GET /api/embedding-models   + POST /api/embedding-models/     │
│                                       activate                   │
│                                                                  │
│  ModelRegistryService ─────────► EmbeddingModelFactory            │
│  (启动加载 + 运行时刷新)         (按 ProviderType 创建)           │
│          │                              │                        │
│          ▼                              ▼                        │
│  DelegatingEmbeddingModel ◄──── setDelegate()                    │
│  (Spring Bean, VectorStore 持有)                                  │
│          │                                                       │
│          ▼                                                       │
│  Spring AI VectorStore (QdrantVectorStore)                        │
│          │                                                       │
│          ▼                                                       │
│  QdrantVectorStoreImpl → VectorMemoryStore                       │
└─────────────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────┐     ┌──────────────────┐
│  MySQL           │     │  Qdrant          │
│  model_provider  │     │  向量存储         │
│  model_definition│     │                  │
│  + category      │     │                  │
│  + dimensions    │     │                  │
│  memory_config   │     │                  │
│  + embedding.    │     │                  │
│    definition_id │     │                  │
└──────────────────┘     └──────────────────┘
```

## 详细设计

### 1. 数据库变更

#### 1.1 model_definition 表扩展

```sql
-- V37__model_definition_category.sql

-- 1) 新增 category 列，默认 CHAT（现有数据无需修改）
SET @col_cat = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_definition' AND COLUMN_NAME = 'category');
SET @sql_cat = IF(@col_cat = 0,
  'ALTER TABLE `model_definition` ADD COLUMN `category` VARCHAR(16) NOT NULL DEFAULT ''CHAT'' AFTER `model_id`',
  'SELECT 1');
PREPARE stmt_cat FROM @sql_cat;
EXECUTE stmt_cat;
DEALLOCATE PREPARE stmt_cat;

-- 2) 新增 dimensions 列（Embedding 模型专用）
SET @col_dim = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_definition' AND COLUMN_NAME = 'dimensions');
SET @sql_dim = IF(@col_dim = 0,
  'ALTER TABLE `model_definition` ADD COLUMN `dimensions` INT DEFAULT NULL AFTER `category`',
  'SELECT 1');
PREPARE stmt_dim FROM @sql_dim;
EXECUTE stmt_dim;
DEALLOCATE PREPARE stmt_dim;

-- 3) memory_config 插入 embedding.definition_id（默认空，使用 yml 回退）
INSERT INTO memory_config (agent_name, config_key, config_value, description) VALUES
('_global_', 'embedding.definition_id', '', '当前使用的 Embedding 模型 definition ID（空=使用 application.yml 回退配置）')
ON DUPLICATE KEY UPDATE config_key = config_key;
```

#### 1.2 数据模型

| 字段 | 类型 | 说明 |
|------|------|------|
| `category` | `VARCHAR(16)` | `CHAT`（默认）或 `EMBEDDING` |
| `dimensions` | `INT NULL` | Embedding 模型的输出向量维度（Chat 模型为 NULL） |

### 2. EmbeddingModelFactory

**路径**：`intellimate-agent/src/main/java/com/atm/intellimate/agent/model/EmbeddingModelFactory.java`

仿照 `ChatModelFactory`，按 `ProviderType` 创建对应的 `EmbeddingModel` 实例。

```java
@Component
public class EmbeddingModelFactory {

    public EmbeddingModel create(ProviderConfig config, String modelId, int dimensions) {
        return switch (config.type()) {
            case DASHSCOPE -> createDashScope(config, modelId, dimensions);
            case OPENAI_COMPATIBLE -> createOpenAi(config, modelId, dimensions);
            case ANTHROPIC, DEEPSEEK -> throw new UnsupportedOperationException(
                "Provider type " + config.type() + " does not support embedding models");
        };
    }

    private EmbeddingModel createDashScope(ProviderConfig config, String modelId, int dimensions) {
        DashScopeApi.Builder apiBuilder = DashScopeApi.builder().apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel(modelId)
                .withDimensions(dimensions)
                .build();
        return new DashScopeEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options);
    }

    private EmbeddingModel createOpenAi(ProviderConfig config, String modelId, int dimensions) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
            if (hasVersionPath(config.baseUrl())) {
                apiBuilder.embeddingsPath("/embeddings");
            }
        }
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelId)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options);
    }
}
```

**支持的 Provider 映射**：

| ProviderType | Spring AI 实现类 | 适用场景 |
|---|---|---|
| `DASHSCOPE` | `DashScopeEmbeddingModel` | 阿里 text-embedding-v3 等 |
| `OPENAI_COMPATIBLE` | `OpenAiEmbeddingModel` | OpenAI text-embedding-3-small/large、SiliconFlow、智谱等兼容 API |
| `ANTHROPIC` | 不支持 | Anthropic 无 Embedding API |
| `DEEPSEEK` | 不支持 | DeepSeek 无 Embedding API |

### 3. DelegatingEmbeddingModel

**路径**：`intellimate-agent/src/main/java/com/atm/intellimate/agent/model/DelegatingEmbeddingModel.java`

代理模式实现运行时热切换。`VectorStore` 持有此 Bean 的引用，内部 delegate 可随时替换。

```java
public class DelegatingEmbeddingModel implements EmbeddingModel {

    private volatile EmbeddingModel delegate;

    public DelegatingEmbeddingModel() {}

    public DelegatingEmbeddingModel(EmbeddingModel initial) {
        this.delegate = initial;
    }

    public void setDelegate(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    public boolean isInitialized() {
        return delegate != null;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return requireDelegate().call(request);
    }

    @Override
    public float[] embed(Document document) {
        return requireDelegate().embed(document);
    }

    @Override
    public float[] embed(String text) {
        return requireDelegate().embed(text);
    }

    @Override
    public int dimensions() {
        return requireDelegate().dimensions();
    }

    private EmbeddingModel requireDelegate() {
        EmbeddingModel d = delegate;
        if (d == null) {
            throw new IllegalStateException(
                "EmbeddingModel not initialized. Please configure an embedding model in the model management page.");
        }
        return d;
    }
}
```

**关键设计**：
- `volatile` 保证多线程可见性
- `requireDelegate()` 未初始化时抛明确错误，避免 NPE
- 无锁设计，读路径零额外开销

### 4. QdrantVectorStoreConfig 重构

**路径**：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/QdrantVectorStoreConfig.java`

**彻底移除硬编码 DashScope**，改用空初始化的 `DelegatingEmbeddingModel`。

```java
@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.qdrant.host")
public class QdrantVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public DelegatingEmbeddingModel embeddingModel() {
        // 空代理：启动时不绑定任何 Provider
        // ModelRegistryService 加载 DB 配置后通过 setDelegate() 设置实际模型
        // 未配置时向量功能自动禁用（VectorMemoryStore.isAvailable() 返回 false）
        return new DelegatingEmbeddingModel();
    }

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public VectorMemoryStore vectorMemoryStore(VectorStore vectorStore) {
        return new QdrantVectorStoreImpl(vectorStore);
    }
}
```

**变更说明**：
- 移除所有 `DashScopeApi`、`DashScopeEmbeddingModel`、`DashScopeEmbeddingOptions` 引用
- 移除所有 `@Value` yml 回退读取
- `vectorMemoryStore` 不再注入 `EmbeddingModel`（`QdrantVectorStoreImpl` 中该字段是 dead code）
- 不再依赖 `application.yml` 中的 Embedding 配置

### 4.1 application.yml 清理

移除 Embedding 相关的 yml 配置：

```yaml
# 删除以下配置
spring.ai.dashscope:
  embedding:          # ← 整个 embedding 节删除
    options:
      model: ${EMBEDDING_MODEL:text-embedding-v3}
      dimensions: ${EMBEDDING_DIMENSIONS:1024}
```

同时将 `initialize-schema` 改为 `false`（Collection 创建改由 Embedding 模型激活时触发）：

```yaml
spring.ai.vectorstore.qdrant:
  initialize-schema: false  # ← 改为 false
```

### 4.2 VectorMemoryStore.isAvailable() 增强

`QdrantVectorStoreImpl.isAvailable()` 增加 delegate 检查：

```java
@Override
public Mono<Boolean> isAvailable() {
    // 先检查 EmbeddingModel 是否已配置
    if (embeddingModel instanceof DelegatingEmbeddingModel dem && !dem.isInitialized()) {
        return Mono.just(false);
    }
    // 再检查 Qdrant 连通性
    return Mono.fromCallable(() -> { ... }).onErrorReturn(false);
}
```

这样在 `HybridMemoryRetrieval` 中，未配置 Embedding 模型时自动降级到纯关键词检索。

### 5. ModelRegistryService 扩展

**路径**：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ModelRegistryService.java`

启动时加载 Embedding 配置并设置 delegate；提供 `refreshEmbeddingModel()` 供运行时热切换。

```java
// 新增字段
private final EmbeddingModelFactory embeddingModelFactory;
private final DelegatingEmbeddingModel delegatingEmbeddingModel; // @Autowired(required = false)
private final MemoryConfigRepository memoryConfigRepo;

@EventListener(ApplicationReadyEvent.class)
public void onStartup() {
    migrateApiKeyIfNeeded()
            .then(loadAll())
            .then(initEmbeddingModel())
            .subscribe(...);
}

private Mono<Void> initEmbeddingModel() {
    if (delegatingEmbeddingModel == null) return Mono.empty();
    return memoryConfigRepo.findByAgentNameAndConfigKey("_global_", "embedding.definition_id")
            .flatMap(config -> {
                String definitionIdStr = config.getConfigValue();
                if (definitionIdStr == null || definitionIdStr.isBlank()) return Mono.empty();
                return refreshEmbeddingModel(Long.parseLong(definitionIdStr));
            })
            .onErrorResume(e -> {
                log.warn("Embedding model init from DB config failed, keeping yml fallback: {}", e.getMessage());
                return Mono.empty();
            });
}

public Mono<Void> refreshEmbeddingModel(Long definitionId) {
    return definitionRepo.findById(definitionId)
            .zipWhen(def -> providerRepo.findById(def.getProviderId()))
            .doOnNext(tuple -> {
                ModelDefinitionEntity def = tuple.getT1();
                ModelProviderEntity provider = tuple.getT2();
                ProviderConfig pc = toProviderConfig(provider);
                int dims = def.getDimensions() != null ? def.getDimensions() : 1024;
                EmbeddingModel model = embeddingModelFactory.create(pc, def.getModelId(), dims);
                delegatingEmbeddingModel.setDelegate(model);
                log.info("Embedding model switched to: provider='{}', model='{}', dimensions={}",
                         provider.getName(), def.getModelId(), dims);
            })
            .then();
}
```

**数据流**：
1. 启动 → `loadAll()`（Chat 模型加载） → `initEmbeddingModel()`
2. 读 `memory_config` 中的 `embedding.definition_id`
3. 有值 → 查 `model_definition` + `model_provider` → 创建 `EmbeddingModel` → 热替换 delegate → 确保 Qdrant Collection 存在
4. 无值或失败 → delegate 保持 null → 向量功能自动禁用，降级到关键词检索

### 6. ModelConfig / ModelDefinitionEntity 扩展

#### 6.1 ModelConfig

```java
// intellimate-agent/.../model/ModelConfig.java
public record ModelConfig(
        Long definitionId,
        Long providerId,
        String modelId,
        String displayName,
        String category,    // 新增: "CHAT" | "EMBEDDING"
        Integer dimensions  // 新增: Embedding 模型维度，Chat 为 null
) {}
```

#### 6.2 ModelDefinitionEntity

```java
// intellimate-gateway/.../entity/ModelDefinitionEntity.java
// 新增字段
private String category;    // "CHAT" 或 "EMBEDDING"
private Integer dimensions; // Embedding 模型维度
// + getter/setter
```

#### 6.3 ModelDTO

```java
// intellimate-gateway/.../dto/ModelDTO.java
// record 新增字段
String category,
Integer dimensions
```

### 7. API 端点

#### 7.1 获取 Embedding 模型列表

```
GET /api/embedding-models
```

响应格式（与 `/api/models` 一致，但仅返回 `category=EMBEDDING` 的模型）：

```json
[
  {
    "providerId": 1,
    "providerName": "阿里 DashScope",
    "providerType": "DASHSCOPE",
    "models": [
      {
        "id": 10,
        "modelId": "text-embedding-v3",
        "displayName": "DashScope Embedding v3",
        "dimensions": 1024
      }
    ]
  }
]
```

#### 7.2 激活 Embedding 模型

```
POST /api/embedding-models/activate
Content-Type: application/json

{ "definitionId": 10 }
```

处理逻辑：
1. 验证 `definitionId` 存在且 `category=EMBEDDING`
2. 保存到 `memory_config` (`embedding.definition_id`)
3. 调用 `ModelRegistryService.refreshEmbeddingModel()`
4. 如果维度与当前不同，响应中附带 `dimensionChanged: true` 标记

### 8. 前端变更

#### 8.1 模型管理页（ModelList.tsx）

`ModelInlineForm` 扩展：

| 字段 | 变更 |
|------|------|
| `category` | 新增切换器：Chat / Embedding（默认 Chat） |
| `dimensions` | 选 Embedding 时显示维度输入框（默认 1024） |

`ModelRow` 扩展：
- 在 `modelId` code 标签旁显示 category 徽章（如蓝色 `Chat` / 紫色 `Embedding`）

#### 8.2 记忆配置页（MemoryManagerPage.tsx）

"Embedding 模型"区域改造：
- 移除 `embedding.model` 和 `embedding.dimensions` 的手动文本输入
- 替换为带分组的下拉选择器，数据来源 `/api/embedding-models`
- 选项格式：`厂商名 / 模型名 (维度)`
- 默认占位：「请选择 Embedding 模型」（无 yml 回退选项）
- 未配置时显示灰色提示：「向量检索需要先配置 Embedding 模型，当前为纯关键词检索模式」
- 维度变更时显示黄色警告：「切换后现有向量数据将不兼容，需在 Qdrant 重建 Collection 并重新迁移」

#### 8.3 api.ts 类型更新

```typescript
// ModelDefinitionDto 新增
export interface ModelDefinitionDto {
  // ...existing...
  category: string;       // "CHAT" | "EMBEDDING"
  dimensions: number | null;
}

// ModelDefinitionCreate 新增
export interface ModelDefinitionCreate {
  // ...existing...
  category?: string;
  dimensions?: number | null;
}

// 新增
export interface EmbeddingModelGroup {
  providerId: number;
  providerName: string;
  providerType: string;
  models: { id: number; modelId: string; displayName: string; dimensions: number }[];
}

export function fetchEmbeddingModels(): Promise<EmbeddingModelGroup[]> {
  return apiFetch<EmbeddingModelGroup[]>("/api/embedding-models");
}

export function activateEmbeddingModel(definitionId: number): Promise<{ success: boolean; dimensionChanged: boolean }> {
  return apiFetch("/api/embedding-models/activate", {
    method: "POST",
    body: JSON.stringify({ definitionId }),
  });
}
```

### 9. ChatModelRegistry 过滤

`ModelRegistryService.loadAll()` 中，传给 `ChatModelRegistry.refreshAll()` 的 definitions 需要过滤：

```java
List<ModelConfig> chatConfigs = definitions.stream()
        .filter(d -> !"EMBEDDING".equals(d.getCategory()))
        .map(this::toModelConfig)
        .toList();
registry.refreshAll(providerConfigs, chatConfigs);
```

避免 Embedding 模型定义被当作 Chat 模型注册到 `ChatModelRegistry`。

### 10. QdrantVectorStoreImpl 清理

移除 `QdrantVectorStoreImpl` 构造器中未使用的 `EmbeddingModel` 参数（dead code 清理）。

## 向后兼容

| 场景 | 行为 |
|------|------|
| 用户未在页面配置 Embedding 模型 | `embedding.definition_id` 为空 → 向量功能禁用 → 降级到纯关键词检索 |
| 现有 `model_definition` 记录 | `category` 默认 `CHAT`，`dimensions` 默认 `NULL` → 无需修改 |
| 删除已激活的 Embedding 模型 | delegate 保持最后设置的值，下次启动时加载失败 → 向量功能禁用 |
| Qdrant 未部署 | 整个 `QdrantVectorStoreConfig` 不激活，`DelegatingEmbeddingModel` 不创建 |
| 从旧版本升级 | yml 中的 Embedding 配置被忽略，需在页面重新配置 Embedding 模型 |

## 风险与注意事项

1. **维度变更不可逆**：切换到不同维度的 Embedding 模型后，Qdrant 中的现有向量数据不兼容，需要重建 Collection + 重新迁移
2. **Provider 限制**：仅 DASHSCOPE 和 OPENAI_COMPATIBLE 支持 Embedding，选择 Anthropic/DeepSeek 厂商下的模型定义时前端应过滤掉 Embedding category
3. **并发安全**：`DelegatingEmbeddingModel` 使用 `volatile` 保证可见性，但不保证切换瞬间的请求原子性（可接受：切换频率极低）

## 不在此次范围

- ANTHROPIC / DEEPSEEK Provider 的 Embedding 支持
- 维度变更后的自动重新迁移
- Embedding 模型的连通性测试端点
- 多 Embedding 模型同时使用（当前设计为全局单一模型）

## 变更文件清单

| 层 | 文件 | 变更 |
|---|---|---|
| 迁移 | `V37__model_definition_category.sql` | 新建 |
| Agent | `EmbeddingModelFactory.java` | 新建 |
| Agent | `DelegatingEmbeddingModel.java` | 新建 |
| Agent | `ModelConfig.java` | 加 `category` + `dimensions` |
| Gateway | `ModelDefinitionEntity.java` | 加 `category` + `dimensions` |
| Gateway | `ModelDTO.java` | 加 `category` + `dimensions` |
| Gateway | `ModelDefinitionRepository.java` | 加按 category 查询方法 |
| Gateway | `ModelDefinitionController.java` | 加 embedding-models 端点 |
| Gateway | `QdrantVectorStoreConfig.java` | 移除硬编码，改用空 DelegatingEmbeddingModel |
| Gateway | `QdrantVectorStoreImpl.java` | 移除无用 EmbeddingModel 参数，isAvailable() 增强 |
| Gateway | `application.yml` | 删除 `spring.ai.dashscope.embedding` 节，`initialize-schema` 改 false |
| Gateway | `ModelRegistryService.java` | 加 embedding 加载 + 热切换 |
| Gateway | `MemoryConfigService.java` | 加 embedding.definition_id 默认值 |
| Web | `api.ts` | 加类型 + API |
| Web | `ModelList.tsx` | 加 category 切换器 + dimensions |
| Web | `MemoryManagerPage.tsx` | Embedding 下拉选择器 |
| 测试 | `EmbeddingModelFactoryTest.java` | 新建 |
| 测试 | `DelegatingEmbeddingModelTest.java` | 新建 |
