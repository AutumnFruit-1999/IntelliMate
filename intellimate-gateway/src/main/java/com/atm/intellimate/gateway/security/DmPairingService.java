package com.atm.intellimate.gateway.security;

import com.atm.intellimate.gateway.entity.AllowlistEntryEntity;
import com.atm.intellimate.gateway.entity.PairingRequestEntity;
import com.atm.intellimate.gateway.repository.AllowlistEntryRepository;
import com.atm.intellimate.gateway.repository.PairingRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the DM pairing flow:
 * 1. New user sends a message → generate a 6-digit pairing code
 * 2. Admin runs /approve <code> → approve the pairing
 * 3. Approved users are added to the allowlist
 */
@Service
public class DmPairingService {

    private static final Logger log = LoggerFactory.getLogger(DmPairingService.class);
    private static final int CODE_EXPIRY_MINUTES = 10;

    private final PairingRequestRepository pairingRepository;
    private final AllowlistEntryRepository allowlistRepository;

    public DmPairingService(PairingRequestRepository pairingRepository,
                            AllowlistEntryRepository allowlistRepository) {
        this.pairingRepository = pairingRepository;
        this.allowlistRepository = allowlistRepository;
    }

    /**
     * Create a pairing request for a new sender. Returns the 6-digit code.
     */
    public Mono<String> createPairingRequest(String channelId, String senderId) {
        return pairingRepository.findByChannelIdAndSenderIdAndStatus(channelId, senderId, "pending")
                .filter(existing -> existing.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(PairingRequestEntity::getPairingCode)
                .switchIfEmpty(Mono.defer(() -> {
                    String code = generateCode();
                    PairingRequestEntity request = new PairingRequestEntity();
                    request.setChannelId(channelId);
                    request.setSenderId(senderId);
                    request.setPairingCode(code);
                    request.setStatus("pending");
                    request.setExpiresAt(LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES));
                    request.setCreatedAt(LocalDateTime.now());

                    return pairingRepository.save(request)
                            .doOnSuccess(r -> log.info("Pairing request created: channel={}, sender={}, code={}", channelId, senderId, code))
                            .map(PairingRequestEntity::getPairingCode);
                }));
    }

    /**
     * Approve a pairing request by code. Adds sender to the allowlist.
     */
    public Mono<String> approve(String code) {
        return pairingRepository.findByPairingCodeAndStatus(code, "pending")
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Invalid or expired pairing code: " + code)))
                .flatMap(request -> {
                    if (request.getExpiresAt().isBefore(LocalDateTime.now())) {
                        request.setStatus("expired");
                        return pairingRepository.save(request)
                                .then(Mono.error(new IllegalArgumentException("Pairing code expired: " + code)));
                    }

                    request.setStatus("approved");
                    request.setUpdatedAt(LocalDateTime.now());

                    AllowlistEntryEntity entry = new AllowlistEntryEntity();
                    entry.setChannelId(request.getChannelId());
                    entry.setSenderId(request.getSenderId());
                    entry.setNote("Approved via DM pairing, code=" + code);
                    entry.setCreatedAt(LocalDateTime.now());
                    entry.setDeleted(0);

                    return pairingRepository.save(request)
                            .then(allowlistRepository.save(entry))
                            .doOnSuccess(e -> log.info("Pairing approved: channel={}, sender={}", request.getChannelId(), request.getSenderId()))
                            .map(e -> "Approved: " + request.getSenderId() + " on channel " + request.getChannelId());
                });
    }

    private String generateCode() {
        return String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
