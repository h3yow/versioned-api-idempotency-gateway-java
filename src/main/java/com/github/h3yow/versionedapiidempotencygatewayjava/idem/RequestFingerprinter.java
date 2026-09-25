package com.github.h3yow.versionedapiidempotencygatewayjava.idem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 将请求体规范化后计算指纹：JSON 字段按 key 排序后做 SHA-256。
 * 同一幂等键下，指纹不同即视为"键冲突"（键被用于不同请求）。
 */
@Component
public class RequestFingerprinter {

    private final ObjectMapper objectMapper;

    public RequestFingerprinter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String fingerprint(Object requestBody) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException e) {
            // 无法序列化时退化为稳定的 toString，保证判定仍确定性
            canonical = String.valueOf(requestBody);
        }
        return sha256(canonical);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
