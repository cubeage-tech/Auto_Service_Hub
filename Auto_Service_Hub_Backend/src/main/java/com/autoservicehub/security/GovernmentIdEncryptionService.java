package com.autoservicehub.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for Government / Aadhaar ID numbers (AES-256-GCM).
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>Algorithm: AES/GCM/NoPadding — provides both confidentiality and
 *       integrity; a tampered ciphertext will fail decryption rather than
 *       silently returning garbage.</li>
 *   <li>Key: 256-bit, supplied as a Base64-encoded string by the
 *       {@code app.govid.encryption-key} property, which defaults to the
 *       {@code MECHANIC_GOVID_ENCRYPTION_KEY} environment variable. Local
 *       development sets it in the git-ignored {@code application.yml};
 *       deployment sets the environment variable. The key is never logged and
 *       never stored in version-controlled source.</li>
 *   <li>IV: 12-byte random per encryption; prepended to the ciphertext so
 *       each stored blob is self-contained and decryptable without a
 *       separate IV table.</li>
 *   <li>Storage format: {@code Base64(IV || ciphertext)} — a single
 *       base64-encoded string written to the {@code government_id_encrypted}
 *       column.</li>
 *   <li>Masking: only the last 4 characters of the original value are
 *       revealed, e.g. {@code XXXX-XXXX-1234}.</li>
 * </ul>
 *
 * <h3>Key generation (one-time, outside the app)</h3>
 * <pre>
 *   # Generate a 256-bit (32-byte) key and base64-encode it:
 *   openssl rand -base64 32
 *   # Local development: put it in the git-ignored application.yml as
 *   #   app.govid.encryption-key: &lt;output&gt;
 *   # Deployment: set it as an environment variable
 *   export MECHANIC_GOVID_ENCRYPTION_KEY=&lt;output&gt;
 * </pre>
 *
 * <p><b>Restart the backend after changing either source.</b> The key is read
 * once at startup, so a running JVM keeps its old (or missing) key regardless
 * of later environment changes.
 *
 * <h3>Failure behaviour</h3>
 * If the key is absent or invalid the service throws {@link IllegalStateException}
 * at the call site.  The application continues to run; only the Government ID
 * path is blocked.  This means a garage can operate without configuring the
 * key — Government ID will simply not be accepted until the variable is set.
 */
@Service
public class GovernmentIdEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(GovernmentIdEncryptionService.class);

    private static final String ALGORITHM        = "AES/GCM/NoPadding";
    private static final int    GCM_TAG_BITS     = 128;   // authentication tag length
    private static final int    IV_BYTES         = 12;    // 96-bit IV — GCM recommendation
    private static final String KEY_ALGORITHM    = "AES";

    /**
     * Base64-encoded 256-bit AES key.
     *
     * <p>Bound to the {@code app.govid.encryption-key} property so that local
     * development reads its value from the git-ignored {@code application.yml}
     * (like every other local credential there), while deployment overrides it
     * via the {@code MECHANIC_GOVID_ENCRYPTION_KEY} environment variable. An
     * unset/blank value leaves {@code encodedKey} empty, which
     * {@link #isKeyConfigured()} reports as false and {@link #loadKey()}
     * rejects at the call site.
     */
    @Value("${app.govid.encryption-key:}")
    private String encodedKey;

    private final SecureRandom secureRandom = new SecureRandom();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts a Government ID string.
     *
     * @param plaintext the raw ID, e.g. "1234 5678 9012" or "ABCDE1234F"
     * @return base64-encoded {@code IV || ciphertext} ready for DB storage
     * @throws IllegalStateException if the encryption key is not configured
     *                               or is invalid
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        SecretKey key = loadKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV so decrypt() is self-contained
            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,        IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            // Do NOT include the plaintext in this message
            throw new IllegalStateException("Government ID encryption failed.", ex);
        }
    }

    /**
     * Decrypts a stored blob produced by {@link #encrypt}.
     *
     * @param storedBlob base64-encoded {@code IV || ciphertext}
     * @return original plaintext, or {@code null} if {@code storedBlob} is null/blank
     * @throws IllegalStateException if decryption fails (key mismatch, tampered data,
     *                               or key not configured)
     */
    public String decrypt(String storedBlob) {
        if (storedBlob == null || storedBlob.isBlank()) {
            return null;
        }
        SecretKey key = loadKey();
        try {
            byte[] combined  = Base64.getDecoder().decode(storedBlob);
            byte[] iv        = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0,        iv,         0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception ex) {
            throw new IllegalStateException("Government ID decryption failed.", ex);
        }
    }

    /**
     * Returns a masked representation of the Government ID suitable for API
     * responses, e.g. {@code "XXXX XXXX 1234"} for an Aadhaar-style number or
     * {@code "XXXXXX1234F"} for a PAN-style ID.
     *
     * <p>The last four non-whitespace characters are revealed; everything else
     * is replaced with {@code X}.  Whitespace and separators in the original
     * are preserved at their positions so the masked value looks like a
     * natural redaction of the original format.
     *
     * @param plaintext the decrypted plaintext ID
     * @return masked string, or {@code null} if {@code plaintext} is null/blank
     */
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        // Collect positions of non-whitespace chars to find the last 4
        char[] chars = plaintext.toCharArray();
        int nonWsCount = 0;
        for (char c : chars) {
            if (!Character.isWhitespace(c) && c != '-') nonWsCount++;
        }
        int revealFrom = nonWsCount - 4;   // reveal last 4 significant chars
        int seen = 0;
        StringBuilder sb = new StringBuilder(chars.length);
        for (char c : chars) {
            if (Character.isWhitespace(c) || c == '-') {
                sb.append(c);             // preserve separators as-is
            } else {
                if (seen >= revealFrom) {
                    sb.append(c);         // reveal
                } else {
                    sb.append('X');       // mask
                }
                seen++;
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} when the encryption key is configured and appears
     * to be a valid 256-bit Base64 value.  Callers may use this to decide
     * whether to accept Government ID submissions.
     */
    public boolean isKeyConfigured() {
        if (encodedKey == null || encodedKey.isBlank()) return false;
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encodedKey.trim());
            return keyBytes.length == 32;
        } catch (Exception ex) {
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SecretKey loadKey() {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException(
                    "Encryption key for Government ID is not configured. "
                    + "Set the MECHANIC_GOVID_ENCRYPTION_KEY environment variable.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "MECHANIC_GOVID_ENCRYPTION_KEY is not valid Base64.", ex);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "MECHANIC_GOVID_ENCRYPTION_KEY must be a 256-bit (32-byte) key "
                    + "but decoded to " + keyBytes.length + " bytes.");
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
