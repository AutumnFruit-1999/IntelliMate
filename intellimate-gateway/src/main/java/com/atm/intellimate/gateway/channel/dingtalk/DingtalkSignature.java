package com.atm.intellimate.gateway.channel.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 signature verification for DingTalk Outgoing robots.
 */
public final class DingtalkSignature {

    private DingtalkSignature() {
    }

    /**
     * Verifies DingTalk Outgoing robot request signature.
     * Algorithm: HmacSHA256(timestamp + "\n" + secret), Base64-encoded.
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
}
