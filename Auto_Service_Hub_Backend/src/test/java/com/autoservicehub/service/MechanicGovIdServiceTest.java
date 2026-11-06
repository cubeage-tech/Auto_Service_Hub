package com.autoservicehub.service;

import com.autoservicehub.dto.MechanicRequestDTO;
import com.autoservicehub.dto.MechanicResponseDTO;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.exception.GovernmentIdKeyNotConfiguredException;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.security.GovernmentIdEncryptionService;
import com.autoservicehub.service.impl.MechanicServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for Government ID behaviour in {@link MechanicServiceImpl}.
 *
 * Uses a real {@link GovernmentIdEncryptionService} (with a test key injected)
 * so encryption/decryption is exercised end-to-end inside the service logic.
 * Only the repository layer is mocked.
 *
 * Test cases
 * ----------
 * S1  create with governmentId → entity has encrypted blob; response has mask, not plaintext
 * S2  create without governmentId → null blob, maskedGovernmentId absent from response
 * S3  update with null governmentId → existing blob preserved
 * S4  update with blank governmentId → existing blob preserved
 * S5  update with new governmentId → blob replaced; new value decryptable
 * S6  toResponse when key missing → safe fallback mask, no exception propagated
 * S7  masked value is never the raw plaintext
 * S8  salary null for SERVICE_ADVISOR (existing RBAC not broken)
 * S9  salary present for MANAGER (existing RBAC not broken)
 * S10 create with governmentId and no encryption key -> dedicated exception, nothing persisted
 * S11 create without governmentId and no encryption key -> still succeeds
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MechanicGovIdServiceTest {

    /** 32 zero-bytes encoded as Base64 — deterministic, valid 256-bit key for tests. */
    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private static final String AADHAAR        = "1234 5678 9012";
    private static final String AADHAAR_MASKED = "XXXX XXXX 9012";

    @Mock
    MechanicRepository mechanicRepository;

    GovernmentIdEncryptionService encryptionService;
    MechanicServiceImpl           service;

    @BeforeEach
    void setUp() {
        encryptionService = new GovernmentIdEncryptionService();
        ReflectionTestUtils.setField(encryptionService, "encodedKey", TEST_KEY);

        service = new MechanicServiceImpl(mechanicRepository, encryptionService);

        when(mechanicRepository.existsByEmployeeCode(anyString())).thenReturn(false);
        when(mechanicRepository.save(any(Mechanic.class))).thenAnswer(inv -> {
            Mechanic m = inv.getArgument(0);
            if (m.getId() == null) m.setId(1L);
            return m;
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── S1: create with governmentId ──────────────────────────────────────

    @Test
    @DisplayName("S1 — create with governmentId: entity has encrypted blob; response has mask, not plaintext")
    void s1_createWithGovId_storesEncryptedAndReturnsMask() {
        setRole("ROLE_SERVICE_ADVISOR");

        MechanicRequestDTO req = baseRequest("Ravi Kumar");
        req.setGovernmentId(AADHAAR);

        MechanicResponseDTO response = service.create(req);

        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        Mechanic saved = captor.getValue();

        // Encrypted blob present and not equal to plaintext
        assertThat(saved.getGovernmentIdEncrypted())
                .isNotNull()
                .isNotEqualTo(AADHAAR)
                .isNotEqualTo(AADHAAR.toUpperCase());

        // Response has masked value
        assertThat(response.getMaskedGovernmentId()).isEqualTo(AADHAAR_MASKED);

        // Raw plaintext not exposed in response fields
        assertNoPlaintextExposed(response, AADHAAR);
    }

    // ── S2: create without governmentId ──────────────────────────────────

    @Test
    @DisplayName("S2 — create without governmentId: null blob, no maskedGovernmentId in response")
    void s2_createWithoutGovId_nullBlobAndNoMaskedField() {
        setRole("ROLE_SERVICE_ADVISOR");

        MechanicResponseDTO response = service.create(baseRequest("Priya Sharma"));

        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getGovernmentIdEncrypted()).isNull();
        assertThat(response.getMaskedGovernmentId()).isNull();
    }

    // ── S3: update — null governmentId preserves existing ────────────────

    @Test
    @DisplayName("S3 — update with null governmentId preserves existing encrypted blob")
    void s3_updateNullGovId_preservesExistingBlob() {
        String existingBlob = encryptionService.encrypt("9876 5432 1098");
        stubFindById(existingBlob);

        MechanicRequestDTO req = baseRequest("Ravi Kumar");
        req.setGovernmentId(null);

        service.update(1L, req);

        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getGovernmentIdEncrypted()).isEqualTo(existingBlob);
    }

    // ── S4: update — blank governmentId preserves existing ───────────────

    @Test
    @DisplayName("S4 — update with blank governmentId preserves existing encrypted blob")
    void s4_updateBlankGovId_preservesExistingBlob() {
        String existingBlob = encryptionService.encrypt("9876 5432 1098");
        stubFindById(existingBlob);

        MechanicRequestDTO req = baseRequest("Ravi Kumar");
        req.setGovernmentId("   ");   // blank → treated as omitted

        service.update(1L, req);

        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        assertThat(captor.getValue().getGovernmentIdEncrypted()).isEqualTo(existingBlob);
    }

    // ── S5: update — new governmentId replaces existing ──────────────────

    @Test
    @DisplayName("S5 — update with new governmentId replaces blob; new value decryptable")
    void s5_updateNewGovId_replacesExistingBlob() {
        String oldBlob = encryptionService.encrypt("9876 5432 1098");
        stubFindById(oldBlob);

        MechanicRequestDTO req = baseRequest("Ravi Kumar");
        req.setGovernmentId(AADHAAR);

        MechanicResponseDTO response = service.update(1L, req);

        ArgumentCaptor<Mechanic> captor = ArgumentCaptor.forClass(Mechanic.class);
        verify(mechanicRepository).save(captor.capture());
        String newBlob = captor.getValue().getGovernmentIdEncrypted();

        // Blob must differ from old one
        assertThat(newBlob).isNotNull().isNotEqualTo(oldBlob);

        // Decrypt to verify new value was actually stored
        assertThat(encryptionService.decrypt(newBlob)).isEqualTo(AADHAAR.toUpperCase());

        // Response reflects new masked value
        assertThat(response.getMaskedGovernmentId()).isEqualTo(AADHAAR_MASKED);
    }

    // ── S6: key missing → safe fallback in response ───────────────────────

    @Test
    @DisplayName("S6 — decryption failure returns safe fallback mask without throwing")
    void s6_decryptionFailure_safeFallbackMask() {
        String blob = encryptionService.encrypt(AADHAAR);

        // Remove key after encryption — simulates key rotation / mis-config at read time
        ReflectionTestUtils.setField(encryptionService, "encodedKey", null);

        when(mechanicRepository.findById(1L)).thenReturn(Optional.of(existingMechanic(blob)));

        MechanicResponseDTO response = service.getById(1L);

        // Must not throw; must return a safe placeholder
        assertThat(response.getMaskedGovernmentId()).isEqualTo("XXXX XXXX XXXX");
    }

    // ── S7: masked value never equals plaintext ───────────────────────────

    @Test
    @DisplayName("S7 — maskedGovernmentId is never equal to the raw plaintext")
    void s7_maskedNeverEqualsPlaintext() {
        setRole("ROLE_SERVICE_ADVISOR");
        MechanicRequestDTO req = baseRequest("Test");
        req.setGovernmentId(AADHAAR);

        MechanicResponseDTO response = service.create(req);

        assertThat(response.getMaskedGovernmentId())
                .isNotEqualTo(AADHAAR)
                .isNotEqualTo(AADHAAR.toUpperCase());
    }

    // ── S8: salary null for SERVICE_ADVISOR ──────────────────────────────

    @Test
    @DisplayName("S8 — salary null for SERVICE_ADVISOR (existing salary RBAC intact)")
    void s8_salaryNullForServiceAdvisor() {
        setRole("ROLE_SERVICE_ADVISOR");
        MechanicRequestDTO req = baseRequest("Test");
        req.setSalary(BigDecimal.valueOf(50000));

        MechanicResponseDTO response = service.create(req);

        assertThat(response.getSalary()).isNull();
    }

    // ── S9: salary present for MANAGER ───────────────────────────────────

    @Test
    @DisplayName("S9 — salary present for MANAGER role (existing salary RBAC intact)")
    void s9_salaryPresentForManager() {
        setRole("ROLE_MANAGER");
        MechanicRequestDTO req = baseRequest("Test");
        req.setSalary(BigDecimal.valueOf(50000));

        MechanicResponseDTO response = service.create(req);

        assertThat(response.getSalary()).isEqualByComparingTo("50000");
    }

    // ── S10: key not configured + Government ID submitted ─────────────────

    @Test
    @DisplayName("S10 — create with governmentId and no encryption key throws a dedicated, non-leaking exception")
    void s10_createWithGovIdAndNoKey_throwsGovIdKeyNotConfigured() {
        setRole("ROLE_SERVICE_ADVISOR");

        // Simulates MECHANIC_GOVID_ENCRYPTION_KEY being absent/invalid on the server
        ReflectionTestUtils.setField(encryptionService, "encodedKey", null);

        MechanicRequestDTO req = baseRequest("Keyless Mechanic");
        req.setGovernmentId(AADHAAR);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(GovernmentIdKeyNotConfiguredException.class)
                // The message must identify the fix without echoing the ID.
                .hasMessageContaining("MECHANIC_GOVID_ENCRYPTION_KEY")
                .hasMessageNotContaining(AADHAAR)
                .hasMessageNotContaining(AADHAAR.toUpperCase());

        // Nothing may be persisted when the ID cannot be encrypted.
        verify(mechanicRepository, never()).save(any(Mechanic.class));
    }

    @Test
    @DisplayName("S11 — create WITHOUT governmentId still succeeds when no encryption key is configured")
    void s11_createWithoutGovIdAndNoKey_stillSucceeds() {
        setRole("ROLE_SERVICE_ADVISOR");

        ReflectionTestUtils.setField(encryptionService, "encodedKey", null);

        MechanicResponseDTO response = service.create(baseRequest("Keyless Mechanic"));

        assertThat(response.getId()).isNotNull();
        assertThat(response.getMaskedGovernmentId()).isNull();
        verify(mechanicRepository).save(any(Mechanic.class));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private MechanicRequestDTO baseRequest(String name) {
        MechanicRequestDTO req = new MechanicRequestDTO();
        req.setName(name);
        req.setPhone("+919876543210");
        req.setStatus("ACTIVE");
        req.setSkills(new ArrayList<>());
        return req;
    }

    private Mechanic existingMechanic(String encryptedBlob) {
        Mechanic m = new Mechanic();
        m.setId(1L);
        m.setName("Existing Mechanic");
        m.setEmployeeCode("EMP-ABCDEF");
        m.setStatus("ACTIVE");
        m.setGovernmentIdEncrypted(encryptedBlob);
        return m;
    }

    private void stubFindById(String encryptedBlob) {
        when(mechanicRepository.findById(1L))
                .thenReturn(Optional.of(existingMechanic(encryptedBlob)));
    }

    private void setRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user", null,
                        List.of(new SimpleGrantedAuthority(role))));
    }

    private void assertNoPlaintextExposed(MechanicResponseDTO dto, String plaintext) {
        String upper = plaintext.toUpperCase();
        // employeeCode and name must not leak the gov ID
        if (dto.getEmployeeCode() != null) {
            assertThat(dto.getEmployeeCode()).doesNotContain(upper).doesNotContain(plaintext);
        }
        if (dto.getMaskedGovernmentId() != null) {
            assertThat(dto.getMaskedGovernmentId())
                    .isNotEqualTo(plaintext)
                    .isNotEqualTo(upper);
        }
    }
}
