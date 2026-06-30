package com.yourname.idempotency.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.idempotency.api.ErrorResponse;
import com.yourname.idempotency.config.IdempotencyProperties;
import com.yourname.idempotency.model.RequestHash;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Front-door interceptor. Validates the {@code Idempotency-Key} header,
 * resolves the authenticated principal (here: a dev-mode header
 * {@code X-User-Id}), hashes the request body, and stashes the values on the
 * request attributes for the controller to pick up.
 *
 * <p>In production, principal resolution would be handled by a
 * Spring Security {@code OncePerRequestFilter} chained ahead of this. For
 * this reference impl we read {@code X-User-Id} directly.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "idem.userId";
    public static final String ATTR_KEY = "idem.key";
    public static final String ATTR_BODY_HASH = "idem.bodyHash";
    public static final String ATTR_CANONICAL_BODY = "idem.canonicalBody";

    private static final String HEADER_KEY = "Idempotency-Key";
    private static final String HEADER_USER = "X-User-Id";

    private final IdempotencyProperties props;
    private final ObjectMapper mapper;

    public IdempotencyInterceptor(IdempotencyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // Only target the charge endpoint (registered explicitly in AppConfig).
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String key = request.getHeader(HEADER_KEY);
        if (key == null || key.isBlank()) {
            writeError(response, 400, "idempotency_key_required");
            return false;
        }
        if (key.length() > props.maxKeyLength()) {
            writeError(response, 400, "idempotency_key_invalid");
            return false;
        }

        String userIdRaw = request.getHeader(HEADER_USER);
        if (userIdRaw == null || userIdRaw.isBlank()) {
            writeError(response, 401, "unauthorized");
            return false;
        }
        long userId;
        try {
            userId = Long.parseLong(userIdRaw);
        } catch (NumberFormatException e) {
            writeError(response, 401, "unauthorized");
            return false;
        }

        // Read body bytes. The DispatcherServlet uses a
        // ContentCachingRequestWrapper if the InputStreamFilter is registered;
        // otherwise read directly. We wrap defensively here.
        byte[] bodyBytes = readBody(request);
        if (bodyBytes.length == 0) {
            writeError(response, 400, "empty_body");
            return false;
        }
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        String canonical;
        try {
            canonical = RequestHash.canonicalize(bodyStr);
        } catch (IllegalArgumentException e) {
            writeError(response, 400, "invalid_json");
            return false;
        }
        String hash = RequestHash.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));

        request.setAttribute(ATTR_USER_ID, userId);
        request.setAttribute(ATTR_KEY, key);
        request.setAttribute(ATTR_BODY_HASH, hash);
        request.setAttribute(ATTR_CANONICAL_BODY, canonical);
        return true;
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            // After the controller binds, the cache is populated; pre-handle is
            // before that. Read straight from the input stream.
            return wrapper.getInputStream().readAllBytes();
        }
        return request.getInputStream().readAllBytes();
    }

    private void writeError(HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }
}
