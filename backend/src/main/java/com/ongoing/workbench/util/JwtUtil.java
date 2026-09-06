package com.ongoing.workbench.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 极简 JWT（HS256，无第三方依赖）：
 * header.payload.signature，签名 = HMAC-SHA256(secret)。校验含签名与过期时间。
 * 足够支撑本地多用户应用的登录态；密钥建议用环境变量 APP_JWT_SECRET 覆盖。
 */
@Component
public class JwtUtil {

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.ttl-hours:720}")
    private long ttlHours;

    private final ObjectMapper om = new ObjectMapper();

    private byte[] key() {
        if (secret != null && secret.length() >= 32) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
        // 未配置或过短：进程内随机密钥兜底（重启后旧 token 失效，属预期降级）
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    public String sign(String userId, String username) {
        return sign(userId, username, null, ttlHours);
    }

    /** 游客体验令牌：role=guest，仅可浏览（写接口被拦截器 403），有效期最长 24 小时 */
    public String signGuest() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("role", "guest");
        return sign("guest", "游客", extra, Math.min(Math.max(1, ttlHours), 24));
    }

    private String sign(String userId, String username, Map<String, Object> extraClaims, long hours) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userId);
        payload.put("name", username);
        if (extraClaims != null) payload.putAll(extraClaims);
        payload.put("iat", now);
        payload.put("exp", now + Math.max(1, hours) * 3600);
        String data = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64(json(payload).getBytes(StandardCharsets.UTF_8));
        return data + "." + b64(sign(data));
    }

    /** 校验并返回 userId；token 非法/过期返回 null */
    public String verify(String token) {
        Map<String, Object> claims = verifyClaims(token);
        if (claims == null) return null;
        Object sub = claims.get("sub");
        return sub == null ? null : String.valueOf(sub);
    }

    /** 校验并返回完整 claims（含 role 等）；token 非法/过期返回 null */
    public Map<String, Object> verifyClaims(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] expect = sign(parts[0] + "." + parts[1]);
            byte[] got = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expect, got)) return null;
            String body = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = om.readValue(body, Map.class);
            Object exp = claims.get("exp");
            long expSec = (exp instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(exp));
            if (expSec * 1000 < System.currentTimeMillis()) return null;
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("jwt signing failed", e);
        }
    }

    private String json(Object o) {
        try { return om.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
