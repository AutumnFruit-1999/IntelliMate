# IntelliMate 启动问题排查记录

记录项目开发过程中遇到的启动/运行时问题及修复方案。

---

## 问题 #1：HealthEndpoint Bean 冲突

**阶段**：应用启动  
**错误信息**：
```
The bean 'healthEndpoint', defined in class path resource 
[org/springframework/boot/actuate/autoconfigure/health/HealthEndpointConfiguration.class], 
could not be registered. A bean with that name has already been defined in file 
[.../com/atm/intellimate/gateway/http/HealthEndpoint.class] and overriding is disabled.
```

**根因**：  
自定义的 `HealthEndpoint` 类被 Spring 容器解析为名为 `healthEndpoint` 的 Bean，与 `spring-boot-starter-actuator` 自动配置注册的同名 Bean 发生冲突。Spring Boot 默认禁止 Bean 覆盖。

**修复方案**：  
删除自定义的 `HealthEndpoint.java`，直接使用 Actuator 内置的健康检查端点 `/actuator/health`。在 `application.yml` 中配置 Actuator 端点暴露：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

**涉及文件**：
- 删除 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/HealthEndpoint.java`
- 修改 `intellimate-gateway/src/main/resources/application.yml`

---

## 问题 #2 / #3：DashScope 多个 AutoConfiguration 缺少 RestClient.Builder

**阶段**：应用启动  
**错误信息（先后出现两次，不同类）**：
```
# 第一次 (#2)
Parameter 2 of method dashscopeAgentApi in 
com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration 
required a bean of type 'org.springframework.web.client.RestClient$Builder' 
that could not be found.

# 第二次 (#3)  —— 排除 Agent 后暴露
Parameter 2 of method dashscopeEmbeddingModel in 
com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration 
required a bean of type 'org.springframework.web.client.RestClient$Builder' 
that could not be found.
```

**根因**：  
Spring AI Alibaba 1.0.0.2 的 DashScope 自动配置类（Agent、Embedding、Image、Rerank、AudioSpeech、AudioTranscription）内部都依赖 `RestClient.Builder`，这是 Spring MVC（Servlet 栈）提供的组件。IntelliMate 使用 Spring WebFlux（Reactive 栈），只有 `WebClient.Builder`，没有 `RestClient.Builder`。

IntelliMate 只使用 `ChatClient` 进行 LLM 对话，其他能力（向量嵌入、图像生成、语音等）暂不需要。

**修复方案**：  
在启动类上一次性排除所有非 Chat 的自动配置，只保留 `DashScopeChatAutoConfiguration`：

```java
@SpringBootApplication(exclude = {
        DashScopeAgentAutoConfiguration.class,
        DashScopeEmbeddingAutoConfiguration.class,
        DashScopeImageAutoConfiguration.class,
        DashScopeRerankAutoConfiguration.class,
        DashScopeAudioSpeechAutoConfiguration.class,
        DashScopeAudioTranscriptionAutoConfiguration.class
})
```

**涉及文件**：
- 修改 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/IntelliMateApplication.java`

**教训**：  
不要逐个排除 —— 发现第一个时就应排查同一包下所有同类问题。DashScope 自动配置类兼容性：

| 类名 | 功能 | 依赖 RestClient | 需排除 |
|------|------|:---:|:---:|
| `DashScopeChatAutoConfiguration` | 对话模型 | 否 | **保留** |
| `DashScopeAgentAutoConfiguration` | Agent API | 是 | 排除 |
| `DashScopeEmbeddingAutoConfiguration` | 向量嵌入 | 是 | 排除 |
| `DashScopeImageAutoConfiguration` | 图像生成 | 是 | 排除 |
| `DashScopeRerankAutoConfiguration` | 重排序 | 是 | 排除 |
| `DashScopeAudioSpeechAutoConfiguration` | 语音合成 | 是 | 排除 |
| `DashScopeAudioTranscriptionAutoConfiguration` | 语音识别 | 是 | 排除 |

---

## 问题 #4：Flyway 启动后不执行 SQL 迁移脚本

**阶段**：应用启动  
**现象**：  
应用启动成功，但 Flyway 没有自动执行 `V1__init_schema.sql`，数据库中没有创建任何表。启动日志中无 Flyway 相关输出。

**原始配置**：
```yaml
spring:
  datasource:           # JDBC DataSource
    url: jdbc:mysql://...
    username: root
    password: ...
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

**根因**：  
Spring Boot 的 `FlywayAutoConfiguration` 有一个条件 `FlywayDataSourceCondition`，它需要以下三者之一满足：
1. 存在 `DataSource` Bean
2. 存在 JDBC `ConnectionDetails` Bean
3. 配置了 `spring.flyway.url`

在 R2DBC 为主数据源的环境下，虽然引入了 `spring-boot-starter-jdbc` 和 `spring.datasource.*` 配置，但 `DataSource` Bean 的创建可能受到 R2DBC 自动配置的干扰，导致 Flyway 条件不匹配、静默跳过。

**修复方案**：  
给 Flyway 配置独立的 JDBC 连接（`spring.flyway.url` / `user` / `password`），让 Flyway 自己创建内部 DataSource，不再依赖 Spring 的 `DataSource` Bean：

```yaml
spring:
  flyway:
    enabled: true
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:intellimate}?useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true
    user: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:}
    locations: classpath:db/migration
    baseline-on-migrate: true
```

同时移除 `spring-boot-starter-jdbc` 依赖（只保留 `mysql-connector-j` JDBC 驱动供 Flyway 使用），避免 JDBC 自动配置与 R2DBC 冲突。

**涉及文件**：
- 修改 `intellimate-gateway/src/main/resources/application.yml` — 移除 `spring.datasource.*`，改用 `spring.flyway.url/user/password`
- 修改 `intellimate-gateway/pom.xml` — 移除 `spring-boot-starter-jdbc`

**注意**：`spring.flyway.user`（不是 `username`）—— Flyway 的属性名和 DataSource 的不同。

---

## 问题 #5：ChatClient.tools() 与 ToolCallback 类型不兼容

**阶段**：运行时（Agent 调用 LLM 时）  
**错误信息**：
```
java.lang.IllegalStateException: No @Tool annotated methods found in 
MethodToolCallback{toolDefinition=DefaultToolDefinition[name=exec, ...]}.
Did you mean to pass a ToolCallback or ToolCallbackProvider? 
If so, you have to use .toolCallbacks() instead of .tool()
```

**调用栈关键路径**：
```
at org.springframework.ai.tool.method.MethodToolCallbackProvider.assertToolAnnotatedMethodsPresent
at org.springframework.ai.support.ToolCallbacks.from
at org.springframework.ai.chat.client.DefaultChatClient$DefaultChatClientRequestSpec.tools
at com.atm.intellimate.agent.runtime.AgentRuntime.executeRun
```

**根因**：  
Spring AI 的 `ChatClient` 提供了两组不同的 API 来注册工具：

| 方法 | 期望参数类型 | 用途 |
|------|------------|------|
| `.tools(Object...)` | 带有 `@Tool` 注解方法的**原始 Bean** | Spring AI 自动扫描 Bean 上的 `@Tool` 方法并包装为 ToolCallback |
| `.toolCallbacks(ToolCallback...)` | 已经包装好的 **ToolCallback** 实例 | 直接传入预构建的 ToolCallback |

`ToolsEngine` 通过 `ToolCallbackProvider` 收集到的已经是 `ToolCallback[]`，但 `AgentRuntime.executeRun()` 错误地调用了 `.tools()` 方法。Spring AI 内部尝试将 `ToolCallback` 对象当作普通 Bean，去扫描其上的 `@Tool` 注解方法，自然找不到，于是抛出 `IllegalStateException`。

**修复方案**：  
将 `AgentRuntime.executeRun()` 中的 `.tools()` 改为 `.toolCallbacks()`：

```java
// 修改前
.tools(toolsEngine.getToolCallbacksFor(request.toolsEnabled()))

// 修改后
.toolCallbacks(toolsEngine.getToolCallbacksFor(request.toolsEnabled()))
```

**涉及文件**：
- 修改 `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

**教训**：  
Spring AI 的 tool 注册 API 命名非常相似，容易混淆：
- `.tools()` → 传原始 Bean，框架自动发现 `@Tool` 方法
- `.toolCallbacks()` → 传已构建的 `ToolCallback` 实例
- `.toolNames()` → 按名称引用已注册的工具

使用 `ToolCallbackProvider` / `ToolsEngine` 统一管理工具时，下游消费方必须用 `.toolCallbacks()` 接收。

---

## 问题 #6：AgentController GET 接口 404 — DB 无 agent 记录

**阶段**：运行时（前端打开智能体配置面板时）  
**错误信息**：
```json
{
  "timestamp": "2026-03-11T03:38:45.955+00:00",
  "path": "/api/agent/intellimate",
  "status": 404,
  "error": "Not Found"
}
```

**根因**：  
`AgentController.getAgent()` 直接查询 `agent` 表，找不到记录就抛 404。但 IntelliMate 的设计允许只通过 `application.yml` 配置默认 agent 而不在 DB 中创建记录 —— `AgentConfigService.resolve()` 已经实现了这种回退逻辑，`AgentController` 却没有。

两个 API 的行为不一致：

| 组件 | DB 有记录 | DB 无记录 |
|------|----------|----------|
| `AgentConfigService.resolve()` | 使用 DB 值 | 回退到 yml 默认 |
| `AgentController.getAgent()` (修复前) | 返回 200 | **抛 404** |

**修复方案**：  
1. **GET** 接口：使用 `.defaultIfEmpty()` 替代 `.switchIfEmpty(error)`，DB 无记录时返回 `application.yml` 的默认值
2. **PUT** 接口：当 `updateContextByName()` 影响 0 行时，自动创建新 agent 记录（upsert 语义），而非抛 404

```java
// GET — DB 无记录时回退到 yml 默认
@GetMapping("/{name}")
public Mono<Map<String, Object>> getAgent(@PathVariable String name) {
    return agentRepository.findByName(name)
            .map(this::entityToDto)
            .defaultIfEmpty(defaultDto(name));  // 回退到 yml 默认
}

// PUT — DB 无记录时自动创建
return agentRepository.updateContextByName(name, soulMd, userMd, agentsMd)
        .flatMap(rows -> {
            if (rows > 0) return Mono.just(success);
            return createAndUpdate(name, soulMd, userMd, agentsMd);  // 自动创建
        });
```

**涉及文件**：
- 修改 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/AgentController.java`

**教训**：  
当系统中同一数据有"DB 优先 + yml 回退"的设计模式时，所有消费方都必须统一遵循相同的回退策略。新增 API 端点时应参照 `AgentConfigService` 已有的 `defaultIfEmpty` 模式。

---

## 通用排查指南

### WebFlux 与 Servlet 栈冲突
IntelliMate 使用纯响应式栈（WebFlux + R2DBC），如果引入的第三方依赖自动配置了 Servlet 栈组件（如 `RestClient.Builder`、`RestTemplate`、`HttpServletRequest`），需要通过 `@SpringBootApplication(exclude = ...)` 排除。

### Bean 名称冲突
Spring Boot Actuator、Spring Data 等框架会注册大量内部 Bean，自定义类命名时避免使用：
- `HealthEndpoint` / `InfoEndpoint` — 与 Actuator 冲突
- `DataSource` / `ConnectionFactory` — 与 Spring Data 冲突
- `ObjectMapper` — 与 Jackson 自动配置冲突

### Flyway 在 R2DBC 项目中的正确配置
Flyway 不支持 R2DBC 协议，必须通过 JDBC 执行迁移。在纯 R2DBC 项目中：
- **不要**依赖 `spring.datasource.*` + `spring-boot-starter-jdbc` 方式 —— R2DBC 自动配置可能干扰 DataSource Bean 的创建
- **推荐**使用 `spring.flyway.url` / `spring.flyway.user` / `spring.flyway.password` 显式配置 —— Flyway 自建内部连接，与 R2DBC 完全解耦
- 只需 classpath 上有 JDBC 驱动 JAR（`mysql-connector-j`），不需要 `spring-boot-starter-jdbc`
