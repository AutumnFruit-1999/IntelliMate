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
        if (signature == null || timestamp == null || nonce == null) {
            return false;
        }
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

            int pad = decrypted[decrypted.length - 1];
            byte[] content = Arrays.copyOfRange(decrypted, 0, decrypted.length - pad);

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
