package com.autoservicehub.service.impl;

import com.autoservicehub.dto.MechanicRequestDTO;
import com.autoservicehub.dto.MechanicResponseDTO;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.MechanicSkill;
import com.autoservicehub.exception.GovernmentIdKeyNotConfiguredException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.security.GovernmentIdEncryptionService;
import com.autoservicehub.service.MechanicService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mechanic management (SRS 4.6).
 *
 * <p><strong>Employee-code generation:</strong> when a create request arrives
 * without an {@code employeeCode}, the server generates one in the format
 * {@code EMP-XXXXXX} (six uppercase alphanumeric characters).  Uniqueness is
 * verified against the repository; up to 10 retries are made.
 *
 * <p><strong>Employee-code on update:</strong> a blank/null {@code employeeCode}
 * in the request preserves the existing value; a non-blank value replaces it.
 *
 * <p><strong>Skills:</strong> when {@code skills} is non-null in the request the
 * collection replaces all existing skill rows (full-replace via orphanRemoval).
 * A null {@code skills} field means "leave skills unchanged" on update, and
 * "no skills" on create.
 *
 * <p><strong>Salary RBAC:</strong> salary is included in the response only when
 * the authenticated caller holds ROLE_OWNER or ROLE_MANAGER.
 * For all other roles it is {@code null} and is omitted from the JSON output.
 *
 * <p><strong>Government ID:</strong> encrypted with AES-GCM before storage via
 * {@link GovernmentIdEncryptionService}.  On update, null/blank preserves the
 * existing encrypted value.  Only the masked form is returned in responses.
 * The full plaintext and encrypted blob are never included in API output.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MechanicServiceImpl implements MechanicService {

    private static final Logger log = LoggerFactory.getLogger(MechanicServiceImpl.class);

    /** Roles allowed to read salary values. */
    private static final Set<String> SALARY_READ_ROLES = Set.of(
            "ROLE_OWNER", "ROLE_MANAGER"
    );

    private static final String CODE_CHARS  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int    CODE_LEN    = 6;
    private static final int    MAX_RETRIES = 10;

    private final MechanicRepository          repository;
    private final GovernmentIdEncryptionService encryptionService;
    private final SecureRandom                secureRandom = new SecureRandom();

    // ── CRUD ──────────────────────────────────────────────────────────────

    @Override
    public MechanicResponseDTO create(MechanicRequestDTO request) {
        Mechanic entity = new Mechanic();
        applyScalarFields(request, entity);

        if (entity.getEmployeeCode() == null || entity.getEmployeeCode().isBlank()) {
            entity.setEmployeeCode(generateUniqueCode());
        }

        // Encrypt Government ID if provided
        applyGovernmentId(request.getGovernmentId(), entity, null);

        replaceSkills(entity, request.getSkills());

        return toResponse(repository.save(entity));
    }

    @Override
    public MechanicResponseDTO update(Long id, MechanicRequestDTO request) {
        Mechanic existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + id));

        // Snapshot fields that must be preserved when the request omits them
        String existingCode          = existing.getEmployeeCode();
        String existingEncryptedGovId = existing.getGovernmentIdEncrypted();

        applyScalarFields(request, existing);

        // Preserve employee code when caller sends blank/null
        if (request.getEmployeeCode() == null || request.getEmployeeCode().isBlank()) {
            existing.setEmployeeCode(existingCode);
        }

        // Preserve Government ID when caller sends null/blank (no change intended)
        applyGovernmentId(request.getGovernmentId(), existing, existingEncryptedGovId);

        if (request.getSkills() != null) {
            replaceSkills(existing, request.getSkills());
        }

        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public MechanicResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mechanic not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MechanicResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Mechanic not found: " + id);
        }
        repository.deleteById(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** Applies all scalar (non-collection, non-govId) fields from the request to the entity. */
    private void applyScalarFields(MechanicRequestDTO r, Mechanic e) {
        e.setName(r.getName());
        e.setEmployeeCode(r.getEmployeeCode() != null ? r.getEmployeeCode().trim() : null);
        e.setPhone(r.getPhone());
        e.setExperienceYears(r.getExperienceYears());
        e.setStatus(r.getStatus() != null ? r.getStatus() : "ACTIVE");
        e.setRole(r.getRole() != null ? r.getRole().trim() : null);
        e.setJoiningDate(r.getJoiningDate());
        e.setSalary(r.getSalary());
    }

    /**
     * Handles Government ID encryption with preservation semantics.
     *
     * <ul>
     *   <li>If {@code incoming} is null or blank → keep {@code existingEncrypted}
     *       (null on create = no ID stored; existing value on update = preserve).</li>
     *   <li>If {@code incoming} is non-blank → encrypt and store the new value,
     *       replacing whatever was there.</li>
     * </ul>
     *
     * <p>The incoming plaintext is never logged.
     *
     * @param incoming          raw Government ID from the request (may be null)
     * @param entity            entity to update in place
     * @param existingEncrypted the currently stored encrypted value (null on create)
     */
    private void applyGovernmentId(String incoming, Mechanic entity, String existingEncrypted) {
        if (incoming == null || incoming.isBlank()) {
            // No new value supplied — preserve whatever is already stored
            entity.setGovernmentIdEncrypted(existingEncrypted);
            return;
        }

        // Normalise: strip leading/trailing whitespace only; keep internal structure
        String normalised = incoming.trim().toUpperCase();

        if (!encryptionService.isKeyConfigured()) {
            throw new GovernmentIdKeyNotConfiguredException(
                    "Government ID cannot be stored because the encryption key "
                    + "(MECHANIC_GOVID_ENCRYPTION_KEY) is not configured on this server.");
        }

        // Encrypt and store — plaintext is discarded after this call
        entity.setGovernmentIdEncrypted(encryptionService.encrypt(normalised));
    }

    /**
     * Full-replace the mechanic's skill list.
     * Clears in-place so Hibernate's orphanRemoval fires correctly within
     * the same transaction.
     */
    private void replaceSkills(Mechanic mechanic, List<String> skillNames) {
        mechanic.getSkills().clear();
        if (skillNames == null || skillNames.isEmpty()) {
            return;
        }
        for (String name : skillNames) {
            if (name == null || name.isBlank()) continue;
            MechanicSkill skill = new MechanicSkill();
            skill.setMechanic(mechanic);
            skill.setSkillName(name.trim());
            mechanic.getSkills().add(skill);
        }
    }

    /**
     * Maps a Mechanic entity to its response DTO.
     *
     * <ul>
     *   <li>Salary: only for OWNER/MANAGER.</li>
     *   <li>Government ID: only the masked form is returned; raw and encrypted
     *       values are never included.  Decryption errors are logged and result
     *       in a placeholder mask rather than an exception leaking to the client.</li>
     * </ul>
     */
    private MechanicResponseDTO toResponse(Mechanic e) {
        MechanicResponseDTO dto = new MechanicResponseDTO();
        dto.setId(e.getId());
        dto.setEmployeeCode(e.getEmployeeCode());
        dto.setName(e.getName());
        dto.setPhone(e.getPhone());
        dto.setExperienceYears(e.getExperienceYears());
        dto.setStatus(e.getStatus());
        dto.setRole(e.getRole());
        dto.setJoiningDate(e.getJoiningDate());
        dto.setSkills(
                e.getSkills().stream()
                        .map(MechanicSkill::getSkillName)
                        .collect(Collectors.toCollection(ArrayList::new))
        );
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());

        // Salary — authorized roles only
        if (callerCanReadSalary()) {
            dto.setSalary(e.getSalary());
        }

        // Government ID — masked form only; never raw or encrypted
        if (e.getGovernmentIdEncrypted() != null) {
            try {
                String plaintext = encryptionService.decrypt(e.getGovernmentIdEncrypted());
                dto.setMaskedGovernmentId(encryptionService.mask(plaintext));
            } catch (Exception ex) {
                // Do NOT propagate — masking failure must never break mechanic reads.
                // Log at WARN level without including the encrypted blob or key.
                log.warn("Could not decrypt/mask Government ID for mechanic id={}: {}",
                        e.getId(), ex.getMessage());
                dto.setMaskedGovernmentId("XXXX XXXX XXXX");  // safe fallback mask
            }
        }
        // else: maskedGovernmentId remains null → omitted from JSON by @JsonInclude(NON_NULL)

        return dto;
    }

    private boolean callerCanReadSalary() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SALARY_READ_ROLES::contains);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            StringBuilder sb = new StringBuilder("EMP-");
            for (int i = 0; i < CODE_LEN; i++) {
                sb.append(CODE_CHARS.charAt(secureRandom.nextInt(CODE_CHARS.length())));
            }
            String candidate = sb.toString();
            if (!repository.existsByEmployeeCode(candidate)) {
                return candidate;
            }
        }
        throw new RuntimeException(
                "Could not generate a unique employee code after " + MAX_RETRIES + " attempts.");
    }
}
