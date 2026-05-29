# 多渠道接入（阶段 3）实现计划 — 钉钉适配器

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**前置条件：** 阶段 1+2 计划已完成（管道打通、AbstractChannelAdapter、ChannelsManager 增强、FeishuAdapter、前端管理页均已实现）。

**目标：** 实现钉钉适配器，支持企业内部应用事件订阅和 Outgoing 机器人两种模式。

**架构：** 在已有的 AbstractChannelAdapter 基类上实现 DingtalkAdapter，处理 HMAC-SHA256 验签、事件 JSON 解析、消息收发。复用阶段 2 已建成的管理 UI 和跨渠道会话机制。

**技术栈：** Java 21 / Spring Boot 3.4 / WebFlux / 钉钉开放平台 SDK（dingtalk-sdk）

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-gateway/.../channel/dingtalk/DingtalkAdapter.java` | 钉钉适配器主类 |
| `intellimate-gateway/.../channel/dingtalk/DingtalkEventParser.java` | 钉钉事件解析 |
| `intellimate-gateway/.../channel/dingtalk/DingtalkSignature.java` | HMAC-SHA256 验签工具 |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `intellimate-gateway/pom.xml` | 添加钉钉 SDK 依赖 |
| `intellimate-web/src/components/ChannelConfigModal.tsx` | 钉钉配置表单字段已包含（阶段 2 已预留） |

---

## 任务 1：添加钉钉 SDK 依赖

**文件：**
- 修改：`intellimate-gateway/pom.xml`

- [ ] **步骤 1：添加依赖**

在 `intellimate-gateway/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>dingtalk</artifactId>
    <version>2.1.44</version>
</dependency>
```

- [ ] **步骤 2：验证依赖解析**

运行：`cd intellimate-gateway && mvn dependency:resolve`
预期：成功下载钉钉 SDK 及其传递依赖

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/pom.xml
git commit -m "chore(deps): add DingTalk SDK dependency"
```

---

## 任务 2：钉钉签名验证工具

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkSignature.java`

- [ ] **步骤 1：实现签名验证**

```java
package com.atm.intellimate.gateway.channel.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class DingtalkSignature {

    /**
     * 验证钉钉 Outgoing 机器人的请求签名。
     * 签名算法：HmacSHA256(timestamp + "\n" + secret)
     */
    public static boolean verify(String timestamp, String sign, String secret) {
        if (timestamp == null || sign == null || secret == null) {
            return false;
        }
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String computedSign = Base64.getEncoder().encodeToString(signData);
            return computedSign.equals(sign);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 验证钉钉企业内部应用的事件回调签名。
     * 签名算法：HmacSHA256(timestamp + "\n" + appSecret, body)
     */
    public static boolean verifyEventCallback(String timestamp, String sign, String appSecret, String body) {
        if (timestamp == null || sign == null || appSecret == null) {
            return false;
        }
        try {
            String stringToSign = timestamp + "\n" + appSecret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            String computedSign = Base64.getEncoder().encodeToString(signData);
            return computedSign.equals(sign);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkSignature.java
git commit -m "feat(channel): add DingTalk HMAC-SHA256 signature verification utility"
```

---

## 任务 3：钉钉事件解析器

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkEventParser.java`

- [ ] **步骤 1：实现事件解析**

```java
package com.atm.intellimate.gateway.channel.dingtalk;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public class DingtalkEventParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断是否为 Outgoing 机器人的消息（包含 msgtype 字段）
     */
    public boolean isOutgoingRobotMessage(WebhookRequest request) {
        if (request.body() == null) return false;
        try {
            JsonNode root = objectMapper.readTree(request.body());
            return root.has("msgtype") && root.has("senderStaffId");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断是否为企业应用事件订阅（包含 EventType 字段）
     */
    public boolean isEventSubscription(WebhookRequest request) {
        if (request.body() == null) return false;
        try {
            JsonNode root = objectMapper.readTree(request.body());
            return root.has("EventType") || root.has("eventType");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析 Outgoing 机器人消息。
     * 格式：{ msgtype, text: { content }, senderStaffId, senderNick, conversationId, conversationType, ... }
     */
    public InboundEnvelope parseOutgoingRobot(WebhookRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.body());

            String senderId = root.path("senderStaffId").asText();
            String senderName = root.path("senderNick").asText("");
            String conversationId = root.path("conversationId").asText();
            String conversationType = root.path("conversationType").asText("1");
            String text = root.path("text").path("content").asText("").trim();

            // 去掉 @机器人 的前缀
            if (text.startsWith("@")) {
                int spaceIdx = text.indexOf(" ");
                if (spaceIdx > 0) {
                    text = text.substring(spaceIdx + 1).trim();
                }
            }

            String contextType = "1".equals(conversationType) ? "dm" : "group";
            String contextId = "dm".equals(contextType) ? senderId : conversationId;

            SessionKey sessionKey = new SessionKey("dingtalk", contextType, contextId);

            return new InboundEnvelope(
                    sessionKey,
                    senderId,
                    senderName,
                    text,
                    List.of(),
                    Instant.now(),
                    request.body()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DingTalk outgoing robot message", e);
        }
    }

    /**
     * 解析企业应用事件订阅中的聊天消息事件。
     */
    public InboundEnvelope parseEventSubscription(WebhookRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.body());

            String eventType = root.has("EventType")
                    ? root.path("EventType").asText()
                    : root.path("eventType").asText();

            if (!"chat_send_message".equals(eventType) && !"message".equals(eventType)) {
                return null;
            }

            String senderId = root.path("SenderId").asText(root.path("senderId").asText(""));
            String senderName = root.path("SenderNick").asText(root.path("senderNick").asText(""));
            String conversationId = root.path("ConversationId").asText(root.path("conversationId").asText(""));
            String text = root.path("Text").path("content").asText(
                    root.path("text").path("content").asText("")
            );

            String contextType = conversationId.startsWith("cid") ? "group" : "dm";
            String contextId = "dm".equals(contextType) ? senderId : conversationId;

            SessionKey sessionKey = new SessionKey("dingtalk", contextType, contextId);

            return new InboundEnvelope(
                    sessionKey,
                    senderId,
                    senderName,
                    text,
                    List.of(),
                    Instant.now(),
                    request.body()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DingTalk event subscription", e);
        }
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkEventParser.java
git commit -m "feat(channel): add DingTalk event parser for outgoing robot and event subscription"
```

---

## 任务 4：钉钉适配器

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkAdapter.java`

- [ ] **步骤 1：实现 DingtalkAdapter**

```java
package com.atm.intellimate.gateway.channel.dingtalk;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

@Component
public class DingtalkAdapter extends AbstractChannelAdapter {

    private static final String CHANNEL_ID = "dingtalk";
    private static final String BASE_URL = "https://oapi.dingtalk.com";

    private final DingtalkEventParser eventParser = new DingtalkEventParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebClient webClient;
    private String appKey;
    private String appSecret;
    private String signSecret;
    private String mode;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.appKey = (String) config.get("appKey");
        this.appSecret = (String) config.get("appSecret");
        this.signSecret = (String) config.getOrDefault("signSecret", "");
        this.mode = (String) config.getOrDefault("mode", "outgoing_robot");
        this.webClient = WebClient.builder().baseUrl(BASE_URL).build();

        if ("enterprise_app".equals(mode)) {
            return refreshToken().then();
        }
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        this.accessToken = null;
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        if ("outgoing_robot".equals(mode)) {
            return sendViaWebhook(message);
        } else {
            return sendViaOpenApi(message);
        }
    }

    private Mono<Void> sendViaWebhook(OutboundMessage message) {
        // Outgoing 机器人模式：通过 sessionWebhook 回复
        // sessionWebhook 需要从原始消息中提取并缓存
        // 简化实现：通过 Open API 发送
        return sendViaOpenApi(message);
    }

    private Mono<Void> sendViaOpenApi(OutboundMessage message) {
        return ensureToken()
                .flatMap(token -> {
                    ObjectNode body = objectMapper.createObjectNode();
                    body.put("msgKey", "sampleText");
                    ObjectNode msgParam = body.putObject("msgParam");
                    msgParam.put("content", message.text());

                    String userId = message.sessionKey().contextId();

                    return webClient.post()
                            .uri("/v1.0/robot/oToMessages/batchSend")
                            .header("x-acs-dingtalk-access-token", token)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .doOnNext(resp -> log.debug("[dingtalk] send response: {}", resp))
                            .then();
                });
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        if (eventParser.isOutgoingRobotMessage(request)) {
            return eventParser.parseOutgoingRobot(request);
        } else if (eventParser.isEventSubscription(request)) {
            return eventParser.parseEventSubscription(request);
        }
        log.warn("[dingtalk] unknown message format, raw: {}", request.body());
        return null;
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        if (signSecret == null || signSecret.isEmpty()) {
            return true;
        }
        String timestamp = request.getHeader("timestamp");
        String sign = request.getHeader("sign");
        return DingtalkSignature.verify(timestamp, sign, signSecret);
    }

    @Override
    public Set<MessageType> supportedMessageTypes() {
        return Set.of(MessageType.TEXT);
    }

    @Override
    public JsonNode getConfigSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("appKey").put("type", "string").put("title", "App Key");
        props.putObject("appSecret").put("type", "string").put("title", "App Secret");
        props.putObject("signSecret").put("type", "string").put("title", "签名密钥");
        props.putObject("mode").put("type", "string").put("title", "模式")
                .put("default", "outgoing_robot")
                .put("description", "outgoing_robot 或 enterprise_app");
        props.putObject("defaultAgent").put("type", "string").put("title", "默认 Agent");
        schema.putArray("required").add("appKey").add("appSecret");
        return schema;
    }

    private Mono<String> ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300_000) {
            return Mono.just(accessToken);
        }
        return refreshToken();
    }

    private Mono<String> refreshToken() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appKey", appKey);
        body.put("appSecret", appSecret);

        return webClient.post()
                .uri("/v1.0/oauth2/accessToken")
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    try {
                        JsonNode node = objectMapper.readTree(resp);
                        this.accessToken = node.get("accessToken").asText();
                        int expire = node.path("expireIn").asInt(7200);
                        this.tokenExpiresAt = System.currentTimeMillis() + expire * 1000L;
                        return this.accessToken;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to refresh DingTalk token", e);
                    }
                });
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：端到端验证**

1. 在数据库 `channel_config` 中插入钉钉配置记录
2. 启动应用，确认 DingtalkAdapter 被 ChannelsManager 发现
3. 通过钉钉机器人发送消息，观察日志确认消息进入 pipeline
4. 确认 Agent 回复通过钉钉 API 推送回去

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/dingtalk/DingtalkAdapter.java
git commit -m "feat(channel): implement DingtalkAdapter with outgoing robot and enterprise app modes"
```

---

## 任务 5：前端钉钉配置验证

**文件：**
- 无需修改（ChannelConfigModal 已在阶段 2 中预留了钉钉配置字段）

- [ ] **步骤 1：验证前端表单**

启动前端开发服务器，进入渠道管理页面，点击「添加渠道」→ 选择钉钉，确认以下字段正确显示：
- App Key
- App Secret
- 签名密钥
- 默认 Agent

- [ ] **步骤 2：端到端流程验证**

1. 通过 Web UI 创建钉钉渠道配置
2. 点击「连接」按钮
3. 确认状态变为 CONNECTED
4. 通过钉钉发送消息，确认跨渠道会话共享（同一用户在钉钉和 Web 看到同一对话）

- [ ] **步骤 3：Commit（如有修改）**

```bash
git add -A && git commit -m "fix(web): adjust DingTalk channel config if needed"
```
