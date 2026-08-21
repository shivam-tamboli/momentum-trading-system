package com.momentum.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Configuration
public class EncryptionConfig {

    @Value("${encryption.key}")
    private String encryptionKey;

    @Bean
    public SecretKeySpec secretKeySpec() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] key32Bytes = sha256.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key32Bytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to build AES-256 secret key", e);
        }
    }
}
