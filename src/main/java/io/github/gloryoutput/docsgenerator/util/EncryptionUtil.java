package io.github.gloryoutput.docsgenerator.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 기반 암호화/복호화 유틸리티
 *
 * <p>레포지토리 인증 정보 등 민감 데이터를 암호화하여 DB에 저장합니다.
 * 암호화 키는 환경변수(ENCRYPTION_KEY)에서 로드합니다.</p>
 *
 * @author Lodong
 * @since 1.0.0
 */
public class EncryptionUtil {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private EncryptionUtil() {
    }

    /**
     * 평문을 AES-GCM으로 암호화합니다.
     *
     * @param plainText 평문
     * @param secretKey 32바이트 암호화 키
     * @return Base64 인코딩된 암호문 (IV + ciphertext)
     */
    public static String encrypt(String plainText, String secretKey) {
        try {
            byte[] keyBytes = normalizeKey(secretKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // IV + 암호문을 합쳐서 Base64로 인코딩
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /**
     * AES-GCM으로 암호화된 데이터를 복호화합니다.
     *
     * @param encryptedText Base64 인코딩된 암호문
     * @param secretKey 32바이트 암호화 키
     * @return 복호화된 평문
     */
    public static String decrypt(String encryptedText, String secretKey) {
        try {
            byte[] keyBytes = normalizeKey(secretKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            byte[] combined = Base64.getDecoder().decode(encryptedText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    /** 키를 32바이트(AES-256)로 정규화 */
    private static byte[] normalizeKey(String key) {
        byte[] keyBytes = new byte[32];
        byte[] input = key.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(input, 0, keyBytes, 0, Math.min(input.length, 32));
        return keyBytes;
    }
}
