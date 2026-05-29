package com.atm.intellimate.gateway.channel;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory store for short-lived account binding codes (6 digits, 5-minute TTL).
 */
@Service
public class ChannelBindingCodeService {

    public static final int EXPIRES_IN_SECONDS = 300;

    private final ConcurrentHashMap<String, BindingEntry> codes = new ConcurrentHashMap<>();

    public record BindingEntry(String userId, Instant expireAt) {}

    public record GeneratedCode(String code, int expiresIn) {}

    public GeneratedCode generateCode(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        purgeExpired();
        String code;
        do {
            code = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        } while (codes.containsKey(code));

        Instant expireAt = Instant.now().plusSeconds(EXPIRES_IN_SECONDS);
        codes.put(code, new BindingEntry(userId, expireAt));
        return new GeneratedCode(code, EXPIRES_IN_SECONDS);
    }

    public Optional<BindingEntry> lookup(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        BindingEntry entry = codes.get(code);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expireAt().isBefore(Instant.now())) {
            codes.remove(code);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void consume(String code) {
        codes.remove(code);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        codes.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(now));
    }

}
