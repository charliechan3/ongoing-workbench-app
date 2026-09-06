package com.ongoing.workbench.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希：PBKDF2WithHmacSHA256（JDK 自带，无第三方依赖）。
 * 存储格式：pbkdf2$<迭代次数>$<盐 base64>$<哈希 base64>
 */
public final class Passwords {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {}

    public static String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(raw, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + b64(salt) + "$" + b64(dk);
    }

    public static boolean verify(String raw, String stored) {
        if (raw == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
        try {
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expect = Base64.getDecoder().decode(parts[3]);
            byte[] dk = derive(raw, salt, iter);
            return MessageDigest.isEqual(expect, dk);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String raw, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iter, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("password hashing failed", e);
        }
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
