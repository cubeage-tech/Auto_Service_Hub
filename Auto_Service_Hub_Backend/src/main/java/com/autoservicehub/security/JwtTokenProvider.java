package com.autoservicehub.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Issues and validates JWT access tokens (SRS 2.4 Security, 19 Security Requirements).
 *
 * <p><strong>Secret policy (SRS 19 - Secrets):</strong> the signing secret is never
 * hard-coded, never logged and never echoed in an exception message. It is validated
 * at startup so a missing, blank, too-short, placeholder or trivial value fails fast
 * and loudly instead of silently signing tokens with a predictable key.
 */
@Component
public class JwtTokenProvider {

    /**
     * Minimum key length in bytes. JJWT's {@code Keys.hmacShaKeyFor(byte[])} throws a
     * {@code WeakKeyException} below 256 bits (32 bytes) and selects the algorithm by
     * length: &gt;= 32 bytes -&gt; HS256, &gt;= 48 -&gt; HS384, &gt;= 64 -&gt; HS512.
     * This project does not pin an algorithm, so 32 bytes is the floor.
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * Substrings indicating a value was never actually configured (case-insensitive).
     * Deliberately conservative: a real random secret will not contain these tokens.
     */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "replace-this", "replace_this", "replacethis",
            "changeme", "change-me", "change_me",
            "placeholder", "your-secret", "your_secret", "yoursecret",
            "example", "sample", "dummy", "todo", "fixme",
            "insert-secret", "put-secret", "secret-here", "secretkeyhere",
            "at-least-32", "minimum-32", "paste_a_random"
    );

    @Value("${app.jwt.secret:}")
    private String secret;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    /**
     * Validates the configured signing secret at startup.
     *
     * @throws IllegalStateException if the secret is missing, blank, shorter than
     *                               {@value #MIN_SECRET_BYTES} bytes, an instructional
     *                               placeholder, or a trivial/repeated value. The
     *                               message never contains the secret value.
     */
    @PostConstruct
    void validateConfiguredSecret() {
        validateSecret(secret);
    }

    /**
     * Package-private so it can be unit tested directly without starting a context.
     *
     * @param candidate the configured secret; may be null
     * @throws IllegalStateException when the candidate is not usable key material
     */
    static void validateSecret(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not configured. Provide it via the JWT_SECRET environment "
                            + "variable or the git-ignored application-local.yml (see "
                            + "application-local.yml.example). No default value is supplied because "
                            + "a fallback signing key would be a security risk.");
        }

        int byteLength = candidate.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < MIN_SECRET_BYTES) {
            // Reports only the required length, never the supplied value.
            throw new IllegalStateException(
                    "app.jwt.secret is too short: at least " + MIN_SECRET_BYTES + " bytes of key "
                            + "material are required (JJWT rejects HMAC keys below 256 bits). "
                            + "Generate one with 'openssl rand -base64 48'.");
        }

        String normalized = candidate.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (normalized.contains(marker)) {
                throw new IllegalStateException(
                        "app.jwt.secret still contains placeholder text. Set a unique random value "
                                + "per environment, e.g. via 'openssl rand -base64 48'. "
                                + "Do not ship an instructional placeholder as a signing key.");
            }
        }

        if (isTrivial(candidate)) {
            throw new IllegalStateException(
                    "app.jwt.secret is a trivial or repeated value and is not acceptable key "
                            + "material. Use a randomly generated value, e.g. 'openssl rand -base64 48'.");
        }
    }

    /**
     * Detects obviously weak material without echoing it: a single repeated character,
     * a short repeating unit, or a value that only reaches the length floor through
     * surrounding whitespace.
     */
    private static boolean isTrivial(String candidate) {
        char first = candidate.charAt(0);
        boolean allSame = true;
        for (int i = 0; i < candidate.length(); i++) {
            if (candidate.charAt(i) != first) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            return true;
        }

        for (int unit = 1; unit <= 8 && unit < candidate.length() / 2; unit++) {
            if (isRepeating(candidate, unit)) {
                return true;
            }
        }

        return candidate.strip().length() < MIN_SECRET_BYTES;
    }

    private static boolean isRepeating(String value, int unitLength) {
        if (value.length() % unitLength != 0) {
            return false;
        }
        String unit = value.substring(0, unitLength);
        for (int i = unitLength; i < value.length(); i += unitLength) {
            if (!value.substring(i, i + unitLength).equals(unit)) {
                return false;
            }
        }
        return true;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String username, String role) {
        String srsRoleName = role == null ? null : role.startsWith("ROLE_") ? role.substring(5) : role;
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);
        return Jwts.builder()
                .subject(username)
                .claim("role", srsRoleName)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key())
                .compact();
    }

    public String getUsername(String token) {
        return Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
