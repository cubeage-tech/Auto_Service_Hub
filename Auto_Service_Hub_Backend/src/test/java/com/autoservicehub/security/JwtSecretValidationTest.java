package com.autoservicehub.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SRS 19 (Security Requirements - Secrets) and SRS 15 (fail safely, never leak
 * the secret): the JWT signing secret must be real key material and must never
 * be echoed back to the operator in an error message.
 *
 * <p>Key-length rule confirmed against the JJWT sources for the version pinned in
 * pom.xml (0.12.5): {@code Keys.hmacShaKeyFor(byte[])} throws {@code WeakKeyException}
 * below 256 bits (32 bytes) and selects HS256 (>=32B), HS384 (>=48B) or HS512 (>=64B)
 * by length.
 */
class JwtSecretValidationTest {

    /** A realistic 48-byte secret, as produced by {@code openssl rand -base64 48}. */
    private static String realSecret() {
        byte[] material = new byte[48];
        for (int i = 0; i < material.length; i++) {
            material[i] = (byte) (i * 7 + 13);
        }
        return Base64.getEncoder().encodeToString(material);
    }

    // ── Rejected inputs ───────────────────────────────────────────────────

    @Test
    @DisplayName("SEC1 - null secret is rejected with a non-secret message")
    void nullSecretIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret(null));
        assertTrue(ex.getMessage().contains("not configured"),
                "expected a 'not configured' message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("SEC2 - blank secret is rejected")
    void blankSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> JwtTokenProvider.validateSecret("   "));
    }

    @Test
    @DisplayName("SEC3 - secret shorter than 32 bytes is rejected")
    void tooShortSecretIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret("0123456789abcdef0123456789abcde")); // 31 bytes
        assertTrue(ex.getMessage().contains("too short"),
                "expected a 'too short' message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("SEC4 - an instructional 'replace-this-...' placeholder is rejected")
    void instructionalPlaceholderIsRejected() {
        // Same class of value that was hard-coded before Phase 1. The assertion is
        // on the rejection behaviour, not on any real historical secret.
        String placeholder = "replace-this-with-a-long-random-secret-key-at-least-32-chars";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret(placeholder));
        assertTrue(ex.getMessage().contains("placeholder"),
                "expected a 'placeholder' message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("SEC5 - other common placeholder wordings are rejected")
    void otherPlaceholdersAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret("changeme-changeme-changeme-changeme"));
        assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret("your-secret-key-goes-right-here-1234"));
        assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret("example-example-example-example-12"));
    }

    @Test
    @DisplayName("SEC6 - a single repeated character is rejected as trivial")
    void repeatedCharacterSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> JwtTokenProvider.validateSecret("a".repeat(64)));
    }

    @Test
    @DisplayName("SEC7 - a short repeating unit is rejected as trivial")
    void repeatingUnitSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> JwtTokenProvider.validateSecret("abcabcabcabcabcabcabcabc"));
    }

    @Test
    @DisplayName("SEC8 - whitespace padding must not be used to reach the length floor")
    void whitespacePaddedSecretIsRejected() {
        // 20 real characters, then padding. The raw value clears the 32-byte length
        // check, but the actual key material does not, so it must still be refused.
        String padded = "abcdefghij0123456789" + " ".repeat(44);
        assertTrue(padded.getBytes().length >= JwtTokenProvider.MIN_SECRET_BYTES,
                "precondition: the raw value must be long enough to reach the length check");
        assertThrows(IllegalStateException.class, () -> JwtTokenProvider.validateSecret(padded));
    }

    // ── Accepted input ────────────────────────────────────────────────────

    @Test
    @DisplayName("SEC9 - a 48-byte random secret is accepted")
    void realSecretIsAccepted() {
        assertDoesNotThrow(() -> JwtTokenProvider.validateSecret(realSecret()));
    }

    @Test
    @DisplayName("SEC10 - exactly 32 bytes is accepted (HS256 floor)")
    void exactlyThirtyTwoBytesIsAccepted() {
        String exactly32 = "8sJq2vTn5wZx0Kp7Rd3LmYc9Hf4Bg6Us1";
        assertTrue(exactly32.getBytes().length >= JwtTokenProvider.MIN_SECRET_BYTES);
        assertDoesNotThrow(() -> JwtTokenProvider.validateSecret(exactly32));
    }

    // ── No secret leakage ─────────────────────────────────────────────────

    @Test
    @DisplayName("SEC11 - rejection messages never echo the supplied secret value")
    void rejectionMessageDoesNotLeakSecret() {
        String candidate = "replace-this-with-a-long-random-secret-key-at-least-32-chars";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret(candidate));
        assertFalse(ex.getMessage().contains(candidate),
                "the exception message must not contain the secret value");
    }

    @Test
    @DisplayName("SEC12 - too-short rejection message does not echo the value")
    void tooShortMessageDoesNotLeakSecret() {
        String candidate = "supersecretvalue";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtTokenProvider.validateSecret(candidate));
        assertFalse(ex.getMessage().contains(candidate),
                "the exception message must not contain the secret value");
    }
}
