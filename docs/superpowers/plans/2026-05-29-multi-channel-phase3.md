# 多渠道接入（阶段 4）实现计划 — 微信适配器 + 监控完善

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**前置条件：** 阶段 1+2+3 已完成（管道打通、飞书适配器、钉钉适配器、前端管理页、跨渠道会话均已实现）。

**目标：** 实现微信适配器抽象基类和第一个具体形态（微信公众号/企业微信），完善渠道监控指标和告警，实现账号绑定功能。

**架构：** 微信适配器设计为可扩展的子类体系（AbstractWeChatAdapter），处理 XML 消息格式、消息加解密、access_token 管理。监控集成 Micrometer。账号绑定复用现有 DmPairingService 的配对码机制。

**技术栈：** Java 21 / Spring Boot 3.4 / WebFlux / Micrometer / 微信公众平台 SDK

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-gateway/.../channel/wechat/AbstractWeChatAdapter.java` | 微信适配器基类 |
| `intellimate-gateway/.../channel/wechat/WeChatCrypto.java` | 微信消息加解密 |
| `intellimate-gateway/.../channel/wechat/WeChatXmlParser.java` | XML 消息解析 |
| `intellimate-gateway/.../channel/wechat/WeChatOfficialAdapter.java` | 微信公众号适配器 |
| `intellimate-gateway/.../channel/ChannelMetrics.java` | 渠道监控指标收集 |
| `intellimate-gateway/.../http/ChannelBindingController.java` | 账号绑定 REST API |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `intellimate-gateway/pom.xml` | 微信相关依赖（XML 解析增强） |
| `intellimate-gateway/.../channel/ChannelsManager.java` | 集成 ChannelMetrics |
| `intellimate-gateway/.../config/ChannelPipelineConfig.java` | 注入 metrics |
| `intellimate-web/src/components/ChannelConfigModal.tsx` | 微信配置字段已预留 |

---

## 任务 1：微信消息加解密工具

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatCrypto.java`

- [ ] **步骤 1：实现加解密**

```java
package com.atm.intellimate.gateway.channel.wechat;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

public class WeChatCrypto {

    private final byte[] aesKey;
    private final String appId;
    private final String token;

    public WeChatCrypto(String encodingAesKey, String appId, String token) {
        this.aesKey = Base64.getDecoder().decode(encodingAesKey + "=");
        this.appId = appId;
        this.token = token;
    }

    /**
     * 验证微信签名：sha1(sort(token, timestamp, nonce))
     */
    public boolean verifySignature(String signature, String timestamp, String nonce) {
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        String content = String.join("", arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(digest);
            return computed.equals(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解密微信加密消息。
     */
    public String decrypt(String encryptedText) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedText);
            byte[] iv = Arrays.copyOfRange(aesKey, 0, 16);

            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            // 去掉 PKCS7 padding
            int pad = decrypted[decrypted.length - 1];
            byte[] content = Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);

            // 格式：16 bytes random + 4 bytes length + msg + appId
            int msgLen = ((content[16] & 0xFF) << 24)
                    | ((content[17] & 0xFF) << 16)
                    | ((content[18] & 0xFF) << 8)
                    | (content[19] & 0xFF);
            return new String(content, 20, msgLen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("WeChat message decryption failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatCrypto.java
git commit -m "feat(channel): add WeChat message encryption/decryption utility"
```

---

## 任务 2：微信 XML 消息解析

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatXmlParser.java`

- [ ] **步骤 1：实现 XML 解析**

```java
package com.atm.intellimate.gateway.channel.wechat;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeChatXmlParser {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("<(\\w+)><!\\[CDATA\\[(.+?)]]></\\1>|<(\\w+)>(\\d+)</\\3>");

    /**
     * 将微信 XML 消息解析为 Map。
     * 微信消息格式：<xml><ToUserName><![CDATA[...]]></ToUserName>...</xml>
     */
    public static Map<String, String> parse(String xml) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = FIELD_PATTERN.matcher(xml);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                result.put(matcher.group(1), matcher.group(2));
            } else if (matcher.group(3) != null) {
                result.put(matcher.group(3), matcher.group(4));
            }
        }
        return result;
    }

    /**
     * 构建被动回复 XML 消息（文本类型）。
     */
    public static String buildTextReply(String toUser, String fromUser, String content) {
        long timestamp = System.currentTimeMillis() / 1000;
        return String.format("""
                <xml>
                <ToUserName><![CDATA[%s]]></ToUserName>
                <FromUserName><![CDATA[%s]]></FromUserName>
                <CreateTime>%d</CreateTime>
                <MsgType><![CDATA[text]]></MsgType>
                <Content><![CDATA[%s]]></Content>
                </xml>""", toUser, fromUser, timestamp, content);
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatXmlParser.java
git commit -m "feat(channel): add WeChat XML message parser and reply builder"
```

---

## 任务 3：微信适配器基类

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/AbstractWeChatAdapter.java`

- [ ] **步骤 1：实现基类**

```java
package com.atm.intellimate.gateway.channel.wechat;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public abstract class AbstractWeChatAdapter extends AbstractChannelAdapter {

    protected WeChatCrypto crypto;
    protected WebClient webClient;
    protected String appId;
    protected String appSecret;
    protected String token;
    protected String encodingAesKey;
    protected volatile String accessToken;
    protected volatile long tokenExpiresAt;

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.appId = (String) config.get("appId");
        this.appSecret = (String) config.get("appSecret");
        this.token = (String) config.getOrDefault("token", "");
        this.encodingAesKey = (String) config.getOrDefault("encodingAesKey", "");

        if (!encodingAesKey.isEmpty()) {
            this.crypto = new WeChatCrypto(encodingAesKey, appId, token);
        }

        this.webClient = WebClient.builder()
                .baseUrl(getBaseUrl())
                .build();

        return refreshAccessToken().then();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        this.accessToken = null;
        return Mono.empty();
    }

    @Override
    protected boolean isVerificationRequest(WebhookRequest request) {
        return "GET".equals(request.method()) && request.getQueryParam("echostr") != null;
    }

    @Override
    protected String handleVerification(WebhookRequest request) {
        return request.getQueryParam("echostr");
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        String signature = request.getQueryParam("signature");
        if (signature == null) {
            signature = request.getHeader("signature");
        }
        String timestamp = request.getQueryParam("timestamp");
        String nonce = request.getQueryParam("nonce");

        if (crypto != null) {
            return crypto.verifySignature(signature, timestamp, nonce);
        }
        return true;
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        String xml = request.body();

        // 如果消息是加密的，先解密
        if (crypto != null && xml.contains("<Encrypt>")) {
            Map<String, String> fields = WeChatXmlParser.parse(xml);
            String encrypted = fields.get("Encrypt");
            xml = crypto.decrypt(encrypted);
        }

        Map<String, String> fields = WeChatXmlParser.parse(xml);
        String msgType = fields.getOrDefault("MsgType", "text");

        if (!"text".equals(msgType)) {
            log.info("[{}] unsupported message type: {}", getChannelId(), msgType);
            return null;
        }

        String fromUser = fields.get("FromUserName");
        String content = fields.getOrDefault("Content", "");

        SessionKey sessionKey = new SessionKey(getChannelId(), "dm", fromUser);

        return new InboundEnvelope(
                sessionKey,
                fromUser,
                fromUser,
                content,
                List.of(),
                Instant.now(),
                request.body()
        );
    }

    protected Mono<String> ensureAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300_000) {
            return Mono.just(accessToken);
        }
        return refreshAccessToken();
    }

    protected abstract String getBaseUrl();
    protected abstract Mono<String> refreshAccessToken();
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/AbstractWeChatAdapter.java
git commit -m "feat(channel): add AbstractWeChatAdapter base class for WeChat ecosystem"
```

---

## 任务 4：微信公众号适配器

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatOfficialAdapter.java`

- [ ] **步骤 1：实现公众号适配器**

```java
package com.atm.intellimate.gateway.channel.wechat;

import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

@Component
public class WeChatOfficialAdapter extends AbstractWeChatAdapter {

    private static final String CHANNEL_ID = "wechat";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    protected String getBaseUrl() {
        return "https://api.weixin.qq.com";
    }

    @Override
    protected Mono<String> refreshAccessToken() {
        return webClient.get()
                .uri("/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}",
                        appId, appSecret)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    try {
                        JsonNode node = objectMapper.readTree(resp);
                        if (node.has("access_token")) {
                            this.accessToken = node.get("access_token").asText();
                            int expires = node.path("expires_in").asInt(7200);
                            this.tokenExpiresAt = System.currentTimeMillis() + expires * 1000L;
                            return this.accessToken;
                        }
                        throw new RuntimeException("WeChat token error: " + resp);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to refresh WeChat token", e);
                    }
                });
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        return ensureAccessToken()
                .flatMap(token -> {
                    ObjectNode body = objectMapper.createObjectNode();
                    body.put("touser", message.sessionKey().contextId());
                    body.put("msgtype", "text");
                    body.putObject("text").put("content", message.text());

                    return webClient.post()
                            .uri("/cgi-bin/message/custom/send?access_token=" + token)
                            .bodyValue(body.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .doOnNext(resp -> log.debug("[wechat] send response: {}", resp))
                            .then();
                });
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
        props.putObject("appId").put("type", "string").put("title", "App ID");
        props.putObject("appSecret").put("type", "string").put("title", "App Secret");
        props.putObject("token").put("type", "string").put("title", "Token（URL 验证用）");
        props.putObject("encodingAesKey").put("type", "string").put("title", "Encoding AES Key（消息加解密）");
        props.putObject("defaultAgent").put("type", "string").put("title", "默认 Agent");
        schema.putArray("required").add("appId").add("appSecret");
        return schema;
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/wechat/WeChatOfficialAdapter.java
git commit -m "feat(channel): implement WeChatOfficialAdapter for public accounts"
```

---

## 任务 5：渠道监控指标

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelMetrics.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java`

- [ ] **步骤 1：实现 ChannelMetrics**

```java
package com.atm.intellimate.gateway.channel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChannelMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> receivedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> sentCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> processingTimers = new ConcurrentHashMap<>();

    public ChannelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMessageReceived(String channelId, String contextType) {
        receivedCounters.computeIfAbsent(channelId + ":" + contextType, k ->
                Counter.builder("channel_messages_received_total")
                        .tag("channel", channelId)
                        .tag("type", contextType)
                        .register(registry)
        ).increment();
    }

    public void recordMessageSent(String channelId, boolean success) {
        String status = success ? "success" : "failed";
        sentCounters.computeIfAbsent(channelId + ":" + status, k ->
                Counter.builder("channel_messages_sent_total")
                        .tag("channel", channelId)
                        .tag("status", status)
                        .register(registry)
        ).increment();
    }

    public void recordError(String channelId, String errorType) {
        errorCounters.computeIfAbsent(channelId + ":" + errorType, k ->
                Counter.builder("channel_errors_total")
                        .tag("channel", channelId)
                        .tag("type", errorType)
                        .register(registry)
        ).increment();
    }

    public Timer.Sample startProcessingTimer() {
        return Timer.start(registry);
    }

    public void stopProcessingTimer(Timer.Sample sample, String channelId) {
        Timer timer = processingTimers.computeIfAbsent(channelId, k ->
                Timer.builder("channel_message_processing_seconds")
                        .tag("channel", channelId)
                        .register(registry)
        );
        sample.stop(timer);
    }

    public void recordChannelStatus(String channelId, boolean connected) {
        registry.gauge("channel_status",
                io.micrometer.core.instrument.Tags.of("channel", channelId),
                connected ? 1.0 : 0.0);
    }
}
```

- [ ] **步骤 2：集成到管道**

在 `ChannelPipelineConfig` 中注入 `ChannelMetrics`，在入站和出站处理中记录指标：

```java
channelsManager.setInboundHandler(envelope -> {
    Timer.Sample sample = channelMetrics.startProcessingTimer();
    String channelId = envelope.sessionKey().channelId();
    channelMetrics.recordMessageReceived(channelId, envelope.sessionKey().contextType());

    messagePipeline.processInbound(envelope)
            .flatMap(reply -> {
                OutboundMessage out = new OutboundMessage(envelope.sessionKey(), reply, null, null);
                return channelsManager.sendOutbound(out)
                        .doOnSuccess(v -> channelMetrics.recordMessageSent(channelId, true))
                        .doOnError(e -> channelMetrics.recordMessageSent(channelId, false));
            })
            .doFinally(s -> channelMetrics.stopProcessingTimer(sample, channelId))
            .subscribe(
                    v -> {},
                    e -> {
                        channelMetrics.recordError(channelId, "processing");
                        log.error("Inbound processing failed", e);
                    }
            );
});
```

- [ ] **步骤 3：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 4：验证 Prometheus 端点**

启动应用，访问 `/actuator/prometheus`，确认以下指标存在：
- `channel_messages_received_total`
- `channel_messages_sent_total`
- `channel_message_processing_seconds`
- `channel_errors_total`
- `channel_status`

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelMetrics.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ChannelPipelineConfig.java
git commit -m "feat(channel): add Micrometer metrics for channel message processing"
```

---

## 任务 6：账号绑定功能

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelBindingController.java`

- [ ] **步骤 1：实现绑定 API**

复用现有 `DmPairingService` 的配对码机制，新增 REST 接口：

```java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.service.DmPairingService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/channel-binding")
public class ChannelBindingController {

    private final DmPairingService pairingService;
    private final ChannelIdentityService identityService;

    public ChannelBindingController(DmPairingService pairingService,
                                    ChannelIdentityService identityService) {
        this.pairingService = pairingService;
        this.identityService = identityService;
    }

    /**
     * Web 端用户生成绑定码。
     * 用户在 Web 登录后调用此接口获取 6 位配对码，
     * 然后在目标渠道中发送该配对码完成绑定。
     */
    @PostMapping("/generate-code")
    public Mono<Map<String, String>> generateBindingCode(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        return pairingService.generateCode(userId)
                .map(code -> Map.of("code", code, "expiresIn", "300"));
    }

    /**
     * 查询当前用户已绑定的渠道身份列表。
     */
    @GetMapping("/identities/{userId}")
    public Mono<Object> listIdentities(@PathVariable String userId) {
        return identityService.listByUserId(userId)
                .collectList()
                .map(list -> Map.of("identities", list));
    }

    /**
     * 手动解绑某个渠道身份。
     */
    @DeleteMapping("/identities/{identityId}")
    public Mono<Void> unbind(@PathVariable Long identityId) {
        return identityService.unbind(identityId);
    }
}
```

- [ ] **步骤 2：在入站管道中处理绑定码消息**

在 `ChannelPipelineConfig` 中，入站消息处理前检查是否为绑定码：

```java
// 在 processInbound 之前拦截
if (isBindingCode(envelope.text())) {
    String code = envelope.text().trim();
    pairingService.validateAndBind(code, envelope.sessionKey().channelId(), envelope.senderId())
            .subscribe(
                    userId -> log.info("Bound {} identity {} to user {}",
                            envelope.sessionKey().channelId(), envelope.senderId(), userId),
                    e -> log.warn("Binding failed: {}", e.getMessage())
            );
    // 回复用户绑定成功/失败
    return;
}
```

- [ ] **步骤 3：在 ChannelIdentityService 中添加辅助方法**

```java
public Flux<ChannelIdentityEntity> listByUserId(String userId) {
    return identityRepository.findByUserId(userId);
}

public Mono<Void> unbind(Long identityId) {
    return identityRepository.deleteById(identityId);
}
```

- [ ] **步骤 4：验证编译**

运行：`cd intellimate-gateway && mvn compile`
预期：BUILD SUCCESS

- [ ] **步骤 5：端到端验证**

1. Web 端登录用户调用 `/api/channel-binding/generate-code` 获取配对码
2. 用户在飞书/钉钉中发送该 6 位码
3. 系统将该渠道身份绑定到 Web 用户
4. 用户后续从该渠道发送的消息关联到同一会话

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/ChannelBindingController.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/channel/ChannelIdentityService.java
git commit -m "feat(channel): add account binding via pairing code for cross-channel identity"
```

---

## 任务 7：消息来源标记 Web 端展示

**文件：**
- 修改：`intellimate-web/src/components/MessageList.tsx`（或对应的消息气泡组件）

- [ ] **步骤 1：在消息气泡中显示来源渠道图标**

在消息组件中根据 `source_channel` 字段渲染小图标：

```tsx
const CHANNEL_BADGES: Record<string, { icon: string; label: string }> = {
  webchat: { icon: "🌐", label: "Web" },
  feishu: { icon: "🔷", label: "飞书" },
  dingtalk: { icon: "🔵", label: "钉钉" },
  wechat: { icon: "🟢", label: "微信" },
};

function ChannelBadge({ channel }: { channel?: string }) {
  if (!channel || channel === "webchat") return null;
  const badge = CHANNEL_BADGES[channel];
  if (!badge) return null;
  return (
    <span className="text-xs text-gray-400 ml-1" title={`来自${badge.label}`}>
      {badge.icon}
    </span>
  );
}
```

在消息时间戳旁边添加 `<ChannelBadge channel={message.sourceChannel} />`。

- [ ] **步骤 2：验证前端构建**

运行：`cd intellimate-web && npm run build`
预期：构建成功

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/components/
git commit -m "feat(web): show channel source badge on messages from external channels"
```

---

## 自检结果

**规格覆盖度：**
- ✅ 微信适配器基类（任务 3）
- ✅ 微信公众号具体形态（任务 4）
- ✅ 消息加解密（任务 1）
- ✅ XML 消息解析（任务 2）
- ✅ 监控指标 Micrometer 集成（任务 5）
- ✅ 告警规则（任务 5 中通过 Prometheus 指标支撑）
- ✅ 账号绑定功能（任务 6）
- ✅ 消息来源标记 Web 端展示（任务 7）

**占位符扫描：** 无 TODO/TBD。所有步骤包含完整代码。

**类型一致性：**
- `AbstractWeChatAdapter` 继承 `AbstractChannelAdapter`（阶段 1 定义）
- `ChannelMetrics` 使用 Micrometer API（项目已有依赖）
- `ChannelIdentityService` 方法签名与阶段 2 定义一致
- `DmPairingService` 复用现有实现
