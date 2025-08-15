package org.bvnk.slackbot.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackSignatureVerifier {
    private static final Logger logger = LoggerFactory.getLogger(SlackSignatureVerifier.class);
    private static final String SIGNING_VERSION = "v0";
    private static final long MAX_TIMESTAMP_AGE_SECONDS = 300; // 5 minutes
    
    private final String signingSecret;
    
    public SlackSignatureVerifier(String signingSecret) {
        this.signingSecret = signingSecret;
    }
    
    public boolean verifySignature(String signature, String timestamp, String body) {
        if (signature == null || timestamp == null || body == null) {
            logger.warn("Missing required parameters for signature verification");
            return false;
        }
        
        // Check timestamp to prevent replay attacks
        try {
            long requestTimestamp = Long.parseLong(timestamp);
            long currentTimestamp = Instant.now().getEpochSecond();
            
            if (Math.abs(currentTimestamp - requestTimestamp) > MAX_TIMESTAMP_AGE_SECONDS) {
                logger.warn("Request timestamp is too old: {}", timestamp);
                return false;
            }
        } catch (NumberFormatException e) {
            logger.error("Invalid timestamp format: {}", timestamp);
            return false;
        }
        
        // Compute expected signature
        String baseString = String.format("%s:%s:%s", SIGNING_VERSION, timestamp, body);

        String expectedSignature = SIGNING_VERSION + "=" + computeHmacSha256(baseString);
        
        // Compare signatures
        boolean isValid = constantTimeEquals(signature, expectedSignature);
        
        if (!isValid) {
            logger.warn("Signature verification failed");
        }
        
        return isValid;
    }
    
    private String computeHmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                signingSecret.getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.error("Error computing HMAC-SHA256", e);
            throw new RuntimeException("Failed to compute HMAC-SHA256", e);
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        
        if (a.length() != b.length()) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        
        return result == 0;
    }
}