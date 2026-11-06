package com.autoservicehub.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * Issues and validates JWT access tokens (SRS 2.4 Security, 19 Security Requirements).
 *
 * <p>The signing secret is supplied by configuration — {@code app.jwt.secret},
 * normally from the {@code JWT_SECRET} environment variable. It is never
 * defaulted, generated or embedded here, so no key material can leak into the
 * source tree (SRS 19: secrets must not be hardcoded).
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** HS256 requires a key of at least 256 bits (32 bytes). */
    private static final int MIN_KEY_BYTES = 32;

    /**
     * Secrets shorter than this still work (see {@link #key()}) but are weak, so
     * the operator is told to set a stronger one.
     */
    private static final int RECOMMENDED_SECRET_BYTES = 32;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiry-ms}")
    private long accessTokenExpiryMs;

    @Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    /**
     * Derives the HMAC signing key from the configured secret.
     *
     * <p>The secret is SHA-256 digested so the key is always exactly 32 bytes,
     * which HS256 requires. Passing the configured bytes straight through does
     * not work for every secret: {@code Keys.hmacShaKeyFor} throws
     * {@code WeakKeyException} for anything under 32 bytes, and the shipped
     * default ({@code change-this-secret-in-env}, 25 bytes) is under that
     * threshold — so token generation blew up at runtime and surfaced as a 500
     * from /login, even for correct credentials.
     *
     * <p>Digesting also means a short or oddly-encoded secret still yields a
     * usable key instead of a runtime failure. It does not add entropy: the
     * secret is only as strong as it was chosen to be, which is why a short one
     * is logged as a warning below.
     */
    private SecretKey key() {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not configured. Set the JWT_SECRET environment variable.");
        }
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < RECOMMENDED_SECRET_BYTES) {
            log.warn("app.jwt.secret is {} bytes; HS256 needs at least {}. A key is still derived, "
                            + "but set a strong JWT_SECRET (32+ random bytes) in any deployed "
                            + "environment.", material.length, MIN_KEY_BYTES);
        }
        return Keys.hmacShaKeyFor(sha256(material));
    }

    private byte[] sha256(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS; its absence means a broken JRE.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Claims and token kinds used to keep access and refresh tokens distinct. */
    private static final String CLAIM_ROLE       = "role";
    private static final String CLAIM_TOKEN_TYPE = "type";
    private static final String TYPE_ACCESS      = "access";
    private static final String TYPE_REFRESH     = "refresh";

    public String generateAccessToken(String username, String role) {
        return buildToken(username, role, accessTokenExpiryMs, TYPE_ACCESS);
    }

    /**
     * Issues a refresh token for the token lifecycle required by SRS 9.
     *
     * <p>Carries the same subject and role as the access token but a different
     * {@code type} claim and a different, longer lifetime, so the two can never be
     * confused for one another. {@link #validateRefreshToken} refuses an access
     * token and {@link #validateToken} refuses a refresh token, which is what stops
     * a long-lived refresh token from being presented as a bearer token against
     * protected endpoints.
     */
    public String generateRefreshToken(String username, String role) {
        return buildToken(username, role, refreshTokenExpiryMs, TYPE_REFRESH);
    }

    private String buildToken(String username, String role, long expiryMs, String tokenType) {
        String srsRoleName = role == null ? null : role.startsWith("ROLE_") ? role.substring(5) : role;
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_ROLE, srsRoleName)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key())
                .compact();
    }

    /** True only for a well-formed, unexpired <em>access</em> token. */
    public boolean validateToken(String token) {
        return validate(token, TYPE_ACCESS);
    }

    /**
     * True only for a well-formed, unexpired <em>refresh</em> token.
     *
     * <p>An access token is rejected here even though it is correctly signed: the
     * two are different credentials with different lifetimes, and accepting an
     * access token as a refresh token would make the refresh endpoint a way to
     * bypass the access token's expiry.
     */
    public boolean validateRefreshToken(String token) {
        return validate(token, TYPE_REFRESH);
    }

    private boolean validate(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            log.warn("JWT validation rejected: reason=missing_token expectedType={}", expectedType);
            return false;
        }
        try {
            var claims = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            // A token minted before the type claim existed has no type, so it is
            // treated as an access token rather than rejected outright.
            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            boolean valid = type == null ? TYPE_ACCESS.equals(expectedType)
                                         : expectedType.equals(type);
            if (!valid) {
                log.warn("JWT validation rejected: reason=token_type_mismatch expectedType={} actualType={}",
                        expectedType, type == null ? "legacy-access" : type);
            }
            return valid;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT validation rejected: reason=expired expectedType={}", expectedType);
            return false;
        } catch (SignatureException ex) {
            log.warn("JWT validation rejected: reason=signature_invalid expectedType={}", expectedType);
            return false;
        } catch (MalformedJwtException ex) {
            log.warn("JWT validation rejected: reason=malformed expectedType={}", expectedType);
            return false;
        } catch (UnsupportedJwtException ex) {
            log.warn("JWT validation rejected: reason=unsupported expectedType={}", expectedType);
            return false;
        } catch (Exception ex) {
            log.warn("JWT validation rejected: reason=validation_error errorType={} expectedType={}",
                    ex.getClass().getSimpleName(), expectedType);
            return false;
        }
    }

    public String getUsername(String token) {
        return Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    /** The role claim carried by a token, or null when it is absent. */
    public String getRole(String token) {
        return Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload().get(CLAIM_ROLE, String.class);
    }
}
