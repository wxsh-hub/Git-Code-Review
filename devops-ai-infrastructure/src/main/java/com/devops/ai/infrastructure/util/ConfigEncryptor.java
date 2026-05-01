package com.devops.ai.infrastructure.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class ConfigEncryptor {

    private static final Logger log = LoggerFactory.getLogger(ConfigEncryptor.class);
    private static final String SALT = "deadbeef";

    private final String secretKey;
    private TextEncryptor encryptor;

    public ConfigEncryptor(@Value("${devops.ai.encrypt.key:devops-ai-default-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    @PostConstruct
    public void init() {
        this.encryptor = Encryptors.text(secretKey, SALT);
    }

    public String encrypt(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }
        return encryptor.encrypt(rawText);
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        try {
            return encryptor.decrypt(encryptedText);
        } catch (Exception e) {
            log.error("Failed to decrypt config value", e);
            return encryptedText;
        }
    }
}
