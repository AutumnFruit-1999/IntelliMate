package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.entity.UserEntity;
import com.atm.intellimate.gateway.repository.UserRepository;
import com.atm.intellimate.gateway.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final ChannelIdentityService channelIdentityService;

    public AuthController(UserRepository userRepository,
                          JwtService jwtService,
                          ChannelIdentityService channelIdentityService) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.channelIdentityService = channelIdentityService;
    }

    @PostMapping("/register")
    public Mono<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String displayName = body.get("displayName");

        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "用户名不能为空且密码至少 4 位"));
        }

        return userRepository.findByUsername(username.trim())
                .flatMap(existing -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在")))
                .switchIfEmpty(Mono.defer(() -> {
                    UserEntity user = new UserEntity();
                    user.setUsername(username.trim());
                    user.setPasswordHash(hashPassword(password));
                    user.setDisplayName(displayName != null ? displayName.trim() : username.trim());
                    user.setStatus("active");
                    user.setCreatedAt(LocalDateTime.now());
                    user.setUpdatedAt(LocalDateTime.now());
                    return userRepository.save(user)
                            .flatMap(saved -> channelIdentityService
                                    .resolveUserId("webchat", String.valueOf(saved.getId()), saved.getDisplayName())
                                    .map(userId -> buildAuthResponse(saved, userId)));
                }));
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.isBlank() || password == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名和密码不能为空"));
        }

        return userRepository.findByUsername(username.trim())
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误")))
                .flatMap(user -> {
                    if (!hashPassword(password).equals(user.getPasswordHash())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
                    }
                    if (!"active".equals(user.getStatus())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用"));
                    }
                    return channelIdentityService
                            .resolveUserId("webchat", String.valueOf(user.getId()), user.getDisplayName())
                            .map(userId -> buildAuthResponse(user, userId));
                });
    }

    @GetMapping("/me")
    public Mono<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractBearerToken(authHeader);
        return jwtService.validateToken(token)
                .map(claims -> channelIdentityService
                        .resolveUserId("webchat", String.valueOf(claims.userId()), null)
                        .map(unifiedUserId -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("userId", claims.userId());
                            result.put("username", claims.username());
                            result.put("unifiedUserId", unifiedUserId);
                            return result;
                        }))
                .orElse(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "无效的 token")));
    }

    private Map<String, Object> buildAuthResponse(UserEntity user, String unifiedUserId) {
        String token = jwtService.generateToken(user.getId(), user.getUsername());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("unifiedUserId", unifiedUserId);
        return result;
    }

    private static String hashPassword(String password) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
