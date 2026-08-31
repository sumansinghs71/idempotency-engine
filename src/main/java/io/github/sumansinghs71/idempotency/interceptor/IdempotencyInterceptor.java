package io.github.sumansinghs71.idempotency.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sumansinghs71.idempotency.api.ErrorResponse;
import io.github.sumansinghs71.idempotency.config.IdempotencyProperties;
import io.github.sumansinghs71.idempotency.model.RequestHash;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Front-door interceptor. Validates the {@code Idempotency-Key} header,
 * resolves the caller identity, hashes the request body, and stashes the values
 * on the request attributes for the controller to pick up.
 *
 * <h2>{@code X-User-Id} is a development/demo identity shim, not authentication</h2>
 *
 * <p>The {@code X-User-Id} header is a <b>development/demo identity shim</b>. It
 * is read verbatim from the request and trusted as-is. It is <b>not</b>
 * authentication and provides <b>no</b> security property whatsoever: anyone who
 * can reach this endpoint can claim to be any user by setting the header, and
 * thereby read that user's cached idempotent responses. It exists only so the
 * idempotency state machine — whose uniqueness scope is
 * {@code (user_id, key)} — has a {@code user_id} to key on without dragging an
 * auth stack into a reference implementation.
 *
 * <p>Deploying this as-is on an untrusted network would be a broken-access-control
 * vulnerability. A real deployment must delete this header handling and derive
 * the principal from an authenticated credential — a Spring Security filter
 * chain ahead of this interceptor, populating {@code SecurityContextHolder} from
 * a verified session, OAuth2/JWT bearer token, or mTLS certificate — and then
 * read the user id from that verified principal rather than from any header the
 * client controls. Nothing else in this class changes.
 *
 * <p>Accordingly, the 401 returned when {@code X-User-Id} is missing or
 * unparseable is an input-validation response, not an authentication decision.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "idem.userId";
    public static final String ATTR_KEY = "idem.key";
    public static final String ATTR_BODY_HASH = "idem.bodyHash";
    public static final String ATTR_CANONICAL_BODY = "idem.canonicalBody";

    private static final String HEADER_KEY = "Idempotency-Key";
    /** Development/demo identity shim header. Unverified. See class javadoc. */
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

        // Development/demo identity shim — see the class javadoc. This value is
        // client-supplied and unverified; it is not an authenticated principal.
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
