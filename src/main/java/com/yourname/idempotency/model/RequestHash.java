package com.yourname.idempotency.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonicalizes a JSON request body and produces a SHA-256 hex digest.
 *
 * <p>Canonicalization rules (matching the de-facto "JSON Canonicalization
 * Scheme" subset we need):
 * <ul>
 *   <li>JSON object keys are sorted lexicographically.</li>
 *   <li>Insignificant whitespace is stripped.</li>
 *   <li>Numbers are emitted as-is (we do not normalize floats — clients
 *       sending the same body across retries will pass; rewriting their
 *       numbers is out of scope).</li>
 * </ul>
 *
 * <p>This is what {@code idempotency_keys.request_params_hash} stores. We
 * compare hashes to detect "same key, different body" → 422.
 */
public final class RequestHash {

    private static final ObjectMapper CANONICAL_MAPPER =
            new ObjectMapper()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.INDENT_OUTPUT, false);

    private RequestHash() {}

    /** Canonicalize and hash. The input must be valid JSON. */
    public static String sha256OfCanonicalized(String json) {
        String canonical = canonicalize(json);
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** Canonicalize JSON: parse, sort keys recursively, re-emit. */
    public static String canonicalize(String json) {
        try {
            Object parsed = CANONICAL_MAPPER.readValue(json, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON body", e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
