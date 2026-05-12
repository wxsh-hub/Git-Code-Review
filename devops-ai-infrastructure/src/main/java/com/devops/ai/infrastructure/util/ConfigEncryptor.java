package com.devops.ai.infrastructure.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ConfigEncryptor {

    private static final Logger log = LoggerFactory.getLogger(ConfigEncryptor.class);

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int KEY_BITS = 128;
    private static final int IV_BYTES = 16;

    private final String secretKey;
    private SecretKeySpec aesKey;

    public ConfigEncryptor(@Value("${devops.ai.encrypt.key:devops-ai-default-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    @PostConstruct
    public void init() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(secretKey.getBytes("UTF-8"));
            byte[] keyBytes = new byte[KEY_BITS / 8];
            System.arraycopy(hash, 0, keyBytes, 0, keyBytes.length);
            aesKey = new SecretKeySpec(keyBytes, "AES");
            log.info("ConfigEncryptor initialized with AES-{}", KEY_BITS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize encryption key", e);
        }
    }

    public String encrypt(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(rawText.getBytes("UTF-8"));

            ByteBuffer buf = ByteBuffer.allocate(iv.length + encrypted.length);
            buf.put(iv);
            buf.put(encrypted);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        // Non-Base64 strings are treated as plaintext (backward compat)
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            ByteBuffer buf = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] encrypted = new byte[buf.remaining()];
            buf.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(iv));
            return new String(cipher.doFinal(encrypted), "UTF-8");
        } catch (Exception e) {
            log.warn("Failed to decrypt config value (treating as plaintext): {}", e.getMessage());
            return encryptedText;
        }
    }

    public void setAesKey(SecretKeySpec aesKey) {
        this.aesKey = aesKey;
    }
}
