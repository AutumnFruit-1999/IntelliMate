package com.atm.intellimate.gateway.security;

import com.atm.intellimate.core.config.IntelliMateProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String DEV_SIGNING_SECRET = "intellimate-dev-jwt-secret-do-not-use-in-prod";
    private static final int TOKEN_VALIDITY_DAYS = 7;

    private final SecretKey signingKey;

    public JwtService(IntelliMateProperties properties) {
        this.signingKey = buildSigningKey(resolveSigningSecret(properties));
    }

    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(TOKEN_VALIDITY_DAYS, ChronoUnit.DAYS)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<JwtClaims> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            Long userId = Long.parseLong(claims.getSubject());
            String username = claims.get("username", String.class);
            if (username == null || username.isBlank()) {
                log.debug("JWT missing username claim");
                return Optional.empty();
            }
            return Optional.of(new JwtClaims(userId, username));
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.debug("Malformed JWT: {}", e.getMessage());
        } catch (SignatureException e) {
            log.debug("Invalid JWT signature: {}", e.getMessage());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static String resolveSigningSecret(IntelliMateProperties properties) {
        String cryptoKey = properties.getSecurity().getCryptoKey();
        if (cryptoKey != null && !cryptoKey.isBlank()) {
            return cryptoKey;
        }
        String authToken = properties.getSecurity().getAuthToken();
        if (authToken != null && !authToken.isBlank()) {
            return authToken;
        }
        log.warn("No JWT signing secret configured — using dev-only default");
        return DEV_SIGNING_SECRET;
    }

    private static SecretKey buildSigningKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            try {
                keyBytes = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public record JwtClaims(Long userId, String username) {
    }
}
