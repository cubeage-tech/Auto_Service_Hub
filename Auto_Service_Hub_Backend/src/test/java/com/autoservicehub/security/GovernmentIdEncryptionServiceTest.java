package com.autoservicehub.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link GovernmentIdEncryptionService}.
 *
 * No Spring context is loaded — the service is constructed directly and the
 * key is injected via {@link ReflectionTestUtils} to mirror what
 * {@code @Value("${app.govid.encryption-key:}")} does at runtime.
 *
 * Test cases
 * ----------
 * E1  Encrypt → Decrypt roundtrip returns original plaintext
 * E2  Two encryptions of the same value produce different ciphertexts (IV randomness)
 * E3  Tampered ciphertext fails decryption with IllegalStateException
 * E4  Null plaintext → encrypt returns null
 * E5  Blank plaintext → encrypt returns null
 * E6  Null storedBlob → decrypt returns null
 * E7  Blank storedBlob → decrypt returns null
 * E8  Missing key → encrypt throws IllegalStateException
 * E9  Missing key → decrypt throws IllegalStateException
 * E10 Invalid Base64 key → encrypt throws IllegalStateException
 * E11 Wrong key length (not 32 bytes) → encrypt throws IllegalStateException
 * E12 isKeyConfigured() returns true for valid key
 * E13 isKeyConfigured() returns false for null key
 * E14 isKeyConfigured() returns false for wrong-length key
 * M1  mask() Aadhaar-style "1234 5678 9012" → "XXXX XXXX 9012"
 * M2  mask() PAN-style "ABCDE1234F" → "XXXXXX234F"
 * M3  mask() value with fewer than 4 chars → reveals all
 * M4  mask() null → null
 * M5  mask() blank → null
 */
class GovernmentIdEncryptionServiceTest {

    /** A valid 256-bit (32-byte) Base64-encoded test key. Generated with: openssl rand -base64 32 */
    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);  // 32 zero-bytes for determinism in tests

    /** A different valid 32-byte key, used for wrong-key tamper tests. */
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString(new byte[]{
                1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,
                17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32
            });

    private GovernmentIdEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new GovernmentIdEncryptionService();
        injectKey(VALID_KEY);
    }

    // ── Encrypt / Decrypt roundtrip ───────────────────────────────────────

    @Test
    @DisplayName("E1 — encrypt then decrypt returns original plaintext")
    void e1_encryptDecryptRoundtrip() {
        String original  = "1234 5678 9012";
        String encrypted = service.encrypt(original);
        assertThat(encrypted).isNotBlank();
        assertThat(service.decrypt(encrypted)).isEqualTo(original);
    }

    @Test
    @DisplayName("E2 — two encryptions of the same plaintext produce different ciphertexts")
    void e2_ivRandomnessProducesDifferentCiphertexts() {
        String plaintext = "ABCDE1234F";
        String first  = service.encrypt(plaintext);
        String second = service.encrypt(plaintext);
        assertThat(first).isNotEqualTo(second);  // different random IV each time
        // Both must still decrypt correctly
        assertThat(service.decrypt(first)).isEqualTo(plaintext);
        assertThat(service.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("E3 — tampered ciphertext fails decryption (GCM integrity check)")
    void e3_tamperedCiphertextFailsDecryption() {
        String encrypted = service.encrypt("1234 5678 9012");
        // Flip the last base64 character to corrupt the ciphertext
        String tampered  = encrypted.substring(0, encrypted.length() - 1) + "A";
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    // ── Null / blank inputs ───────────────────────────────────────────────

    @Test
    @DisplayName("E4 — encrypt(null) returns null")
    void e4_encryptNullReturnsNull() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    @DisplayName("E5 — encrypt blank string returns null")
    void e5_encryptBlankReturnsNull() {
        assertThat(service.encrypt("   ")).isNull();
    }

    @Test
    @DisplayName("E6 — decrypt(null) returns null")
    void e6_decryptNullReturnsNull() {
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("E7 — decrypt blank string returns null")
    void e7_decryptBlankReturnsNull() {
        assertThat(service.decrypt("   ")).isNull();
    }

    // ── Missing / invalid key ─────────────────────────────────────────────

    @Test
    @DisplayName("E8 — encrypt with no key throws IllegalStateException")
    void e8_encryptMissingKeyThrows() {
        injectKey(null);
        assertThatThrownBy(() -> service.encrypt("1234 5678 9012"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("E9 — decrypt with no key throws IllegalStateException")
    void e9_decryptMissingKeyThrows() {
        // Encrypt first with valid key, then remove key before decrypting
        String encrypted = service.encrypt("1234 5678 9012");
        injectKey(null);
        assertThatThrownBy(() -> service.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("E10 — non-Base64 key throws IllegalStateException")
    void e10_invalidBase64KeyThrows() {
        injectKey("this-is-not-valid-base64!!!");
        assertThatThrownBy(() -> service.encrypt("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    @DisplayName("E11 — key with wrong byte length throws IllegalStateException")
    void e11_wrongLengthKeyThrows() {
        // 16-byte key (AES-128) — service requires exactly 32 bytes
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        injectKey(shortKey);
        assertThatThrownBy(() -> service.encrypt("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256-bit");
    }

    // ── isKeyConfigured() ─────────────────────────────────────────────────

    @Test
    @DisplayName("E12 — isKeyConfigured() true for valid 32-byte key")
    void e12_isKeyConfiguredTrueForValidKey() {
        assertThat(service.isKeyConfigured()).isTrue();
    }

    @Test
    @DisplayName("E13 — isKeyConfigured() false for null key")
    void e13_isKeyConfiguredFalseForNull() {
        injectKey(null);
        assertThat(service.isKeyConfigured()).isFalse();
    }

    @Test
    @DisplayName("E14 — isKeyConfigured() false for wrong-length key")
    void e14_isKeyConfiguredFalseForShortKey() {
        injectKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(service.isKeyConfigured()).isFalse();
    }

    // ── mask() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("M1 — Aadhaar-style number masked correctly")
    void m1_maskAadhaarStyle() {
        // "1234 5678 9012" → significant chars: 123456789012
        // Last 4 = 9012 → reveal '9','0','1','2' with space separators preserved
        String masked = service.mask("1234 5678 9012");
        assertThat(masked).isEqualTo("XXXX XXXX 9012");
    }

    @Test
    @DisplayName("M2 — PAN-style ID masked correctly")
    void m2_maskPanStyle() {
        // "ABCDE1234F" → last 4 = "234F"
        String masked = service.mask("ABCDE1234F");
        assertThat(masked).isEqualTo("XXXXXX234F");
    }

    @Test
    @DisplayName("M3 — value with 4 or fewer significant characters reveals all")
    void m3_shortValueRevealsAll() {
        // "1234" → last 4 = "1234" → all revealed
        assertThat(service.mask("1234")).isEqualTo("1234");
    }

    @Test
    @DisplayName("M4 — mask(null) returns null")
    void m4_maskNullReturnsNull() {
        assertThat(service.mask(null)).isNull();
    }

    @Test
    @DisplayName("M5 — mask blank string returns null")
    void m5_maskBlankReturnsNull() {
        assertThat(service.mask("   ")).isNull();
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private void injectKey(String key) {
        ReflectionTestUtils.setField(service, "encodedKey", key);
    }
}
