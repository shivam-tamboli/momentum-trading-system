package com.momentum.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for values at rest — right now, exactly the Alpaca API key/secret on
 * {@link com.momentum.model.User}. Keyed by ENCRYPTION_KEY (a base64-encoded 256-bit key, set in
 * the environment, never committed).
 *
 * Every value this class encrypts is prefixed with ENC_PREFIX. That's not decorative: this class
 * used to be a pass-through no-op (encrypt(x) == x), so every row written before this existed
 * holds genuine plaintext with no prefix at all. decrypt() uses the prefix's presence, not a
 * guess, to tell "real ciphertext" apart from "legacy plaintext" — a legacy value passes through
 * decrypt() unchanged instead of being run through AES/GCM and thrown out as invalid. Skipping
 * that check would mean every existing user's saved Alpaca key stops decrypting the moment this
 * class deploys. See AdminController's one-time migration endpoint for upgrading existing rows.
 */
@Component
public class EncryptionUtil {

    private static final String ENC_PREFIX = "enc:v1:";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;

    public EncryptionUtil(@Value("${encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(ivAndCiphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt value", e);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null) {
            return null;
        }

        // No prefix means this predates real encryption — genuine plaintext, return as-is.
        if (!storedValue.startsWith(ENC_PREFIX)) {
            return storedValue;
        }

        try {
            byte[] ivAndCiphertext = Base64.getDecoder().decode(storedValue.substring(ENC_PREFIX.length()));

            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_LENGTH_BYTES);

            byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(ivAndCiphertext, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt value", e);
        }
    }
}
