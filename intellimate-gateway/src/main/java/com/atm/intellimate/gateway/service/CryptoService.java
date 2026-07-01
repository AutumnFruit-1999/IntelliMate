package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.config.IntelliMateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for sensitive data (API Keys).
 * Format: Base64( IV[12] + ciphertext + tag[16] )
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String PLACEHOLDER = "__PLACEHOLDER__";

    private final byte[] keyBytes;
    private final boolean enabled;

    public CryptoService(IntelliMateProperties props) {
        String key = props.getSecurity().getCryptoKey();
        if (key == null || key.isBlank()) {
            log.warn("intellimate.security.crypto-key is not set; API Key encryption is DISABLED — keys stored in plaintext");
            this.keyBytes = null;
            this.enabled = false;
        } else {
            this.keyBytes = deriveKey(key);
            this.enabled = true;
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        if (!enabled) return plaintext;
        try {
            SecureRandom random = SecureRandom.getInstanceStrong();
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return encrypted;
        if (PLACEHOLDER.equals(encrypted)) return encrypted;
        if (!enabled) return encrypted;
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            if (decoded.length < GCM_IV_LENGTH + 1) {
                return encrypted;
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encrypted;
        } catch (Exception e) {
            log.error("Decryption failed for stored value — possible key mismatch or data corruption", e);
            throw new RuntimeException("Decryption failed — check crypto-key configuration", e);
        }
    }

    public String mask(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    public boolean isPlaceholder(String value) {
        return PLACEHOLDER.equals(value);
    }

    private static byte[] deriveKey(String key) {
        try {
            byte[] salt = "intellimate-aes-key".getBytes(StandardCharsets.UTF_8);
            byte[] ikm = key.getBytes(StandardCharsets.UTF_8);

            // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            // HKDF-Expand: OKM = HMAC-SHA256(PRK, info || 0x01), truncated to 32 bytes
            byte[] info = "aes-256-gcm-key".getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 0x01);
            return mac.doFinal();
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}
