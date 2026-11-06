package com.autoservicehub.service.impl;

import com.autoservicehub.dto.UserRequestDTO;
import com.autoservicehub.dto.UserResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.RoleRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.AuditService;
import com.autoservicehub.service.UserService;
import com.autoservicehub.util.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * User account administration (SRS 8.2, SRS 13 Roles &amp; Permissions,
 * SRS 19 Security Requirements).
 *
 * <h2>What this flow may and may not do</h2>
 *
 * <p>It creates a login for a member of workshop staff — the caller is already
 * signed in and already authorised by the controller's method security. That is
 * why the assignable roles are a fixed list of seven: {@code OWNER},
 * {@code GARAGE_OWNER}, {@code MANAGER}, {@code SERVICE_ADVISOR},
 * {@code MECHANIC}, {@code INVENTORY_MANAGER} and {@code BILLING_USER}.
 * {@code GARAGE_OWNER} is listed beside {@code OWNER}
 * because it is the name a {@code roles} row actually stores the garage owner
 * under, and the create-user dialog asks for it by that name: it is accepted as
 * a role in its own right as well as being the fallback lookup for
 * {@code OWNER} (see {@link #OWNER_STORED_ALIAS}). {@code CUSTOMER} is excluded
 * on purpose: customer logins belong to the customer-portal flow, and accepting
 * one here would turn an internal administration screen into a way of minting
 * customer accounts.
 *
 * <h2>Three deliberate choices</h2>
 *
 * <ul>
 *   <li><b>Role names are resolved against the {@code roles} table, not a
 *       regex.</b> The table is the authority on which roles exist. The list
 *       above only says which of them may be handed out here, so a role that is
 *       offered but has no row is refused with a message naming it rather than
 *       failing later when the user tries to sign in. Input separators are
 *       normalised ({@code "Service Advisor"}, {@code "service-advisor"} and
 *       {@code "SERVICE_ADVISOR"} all mean {@code SERVICE_ADVISOR}), because
 *       that is a spelling difference, not a different role. Where the table
 *       stores the garage owner as {@code GARAGE_OWNER} rather than
 *       {@code OWNER}, that name is accepted here too and resolves straight to
 *       the row it names, while the canonical name is still looked up first
 *       with that existing row as the fallback — either way the account is
 *       linked to a role that is already there rather than gaining or losing
 *       one. An ambiguous label
 *       is <em>not</em> guessed: {@code "Owner/Manager"} collapses to
 *       {@code OWNER_MANAGER}, matches nothing, and is refused — quietly picking
 *       one of the two would be a privilege decision made by a typo.</li>
 *   <li><b>Uniqueness is checked the way sign-in matches.</b>
 *       {@code CustomUserDetailsService} resolves usernames and emails
 *       case-insensitively, so a case-insensitive check is what keeps one
 *       credential from having two owners. Violations are
 *       {@link BusinessRuleException}s, which the API reports as {@code 409}
 *       rather than {@code 500}.</li>
 *   <li><b>The password is hashed and never reproduced.</b> Only the encoder's
 *       output is stored, the response DTO has no credential field at all, and
 *       the audit detail records role and status but never a password.</li>
 * </ul>
 *
 * <p>Bean validation on {@link UserRequestDTO} is the first line of defence for
 * shape (required fields, email format, password length). The checks here are
 * for callers that reach the service directly and for the rules validation
 * cannot express — uniqueness, role resolution and status mapping.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    /** Entity name recorded on this module's audit entries. */
    private static final String AUDIT_ENTITY = "USER";

    /** {@code ROLE_} is a Spring Security prefix, not part of a stored role name. */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * The name some {@code roles} rows store the garage owner under, where
     * {@link AppConstants#ROLE_OWNER} is not a row of its own. Accepted here as
     * a role in its own right — that is the name the create-user dialog sends —
     * and consulted as a fallback lookup for {@code OWNER}:
     * see {@link #resolveOwnerAlias}.
     */
    private static final String OWNER_STORED_ALIAS = "GARAGE_OWNER";

    /** The only roles this administrative flow may hand out (SRS 13). */
    private static final List<String> ASSIGNABLE_ROLES = List.of(
            AppConstants.ROLE_OWNER,
            OWNER_STORED_ALIAS,
            AppConstants.ROLE_MANAGER,
            AppConstants.ROLE_SERVICE_ADVISOR,
            AppConstants.ROLE_MECHANIC,
            AppConstants.ROLE_INVENTORY,
            AppConstants.ROLE_BILLING);

    /** Status wording used by the create-user form. */
    private static final String STATUS_ACTIVE   = "Active";
    private static final String STATUS_INACTIVE = "Inactive";

    private final UserRepository  userRepository;
    private final RoleRepository  roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService    auditService;

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    public UserResponseDTO createUser(UserRequestDTO request) {
        String username = requireText(request.getUsername(), "username");
        String email    = requireText(request.getEmail(), "email");

        assertUsernameAvailable(username);
        assertEmailAvailable(email);

        Role role = resolveRole(request.getRole());

        User entity = new User();
        entity.setUsername(username);
        entity.setEmail(email);
        entity.setFullName(requireText(request.getFullName(), "fullName"));
        entity.setPhone(requireText(request.getPhone(), "phone"));
        entity.setRole(role);
        entity.setActive(resolveActive(request.getStatus()));
        entity.setPasswordHash(passwordEncoder.encode(requirePassword(request.getPassword())));

        User saved = userRepository.save(entity);
        UserResponseDTO response = toResponse(saved);

        // Role and status only — no credential appears in this string.
        // AuditService redacts credential-shaped text as a second defence.
        auditService.recordSuccess(AUDIT_ENTITY, saved.getId(), AuditAction.USER_CREATE,
                "User created: " + saved.getUsername()
                        + " (role " + role.getName()
                        + ", status " + statusLabel(saved.getActive()) + ")");
        return response;
    }

    // ── Read ──────────────────────────────────────────────────────────────

    /**
     * One page of accounts, mapped through the safe projection.
     *
     * <p>{@code readOnly}: this is a plain read, so no write transaction is
     * opened and nothing is flushed. No audit entry is recorded either — the
     * trail records changes, and looking at the list is not one.
     *
     * <p>The role is fetched with the page (see
     * {@link UserRepository#findAll(Pageable)}), so mapping a page does not cost
     * an extra query per row, and every row is mapped by the same
     * {@link #toResponse(User)} the create flow uses — so a listed account
     * carries exactly the fields a created one does, and no credential.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserResponseDTO> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toResponse);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Rejects a username already held by another account, ignoring case.
     *
     * <p>A username is a credential, not a display name: two accounts that
     * differ only in capitalisation would be indistinguishable at sign-in.
     */
    private void assertUsernameAvailable(String username) {
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BusinessRuleException("Username '" + username + "' is already taken.");
        }
    }

    /**
     * Rejects an email already held by another account, ignoring case.
     *
     * <p>The email is the alternative sign-in name and the password-reset
     * address, so a duplicate would send a reset for one account to an address
     * that also names another.
     */
    private void assertEmailAvailable(String email) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException(
                    "Email '" + email + "' is already registered to another account.");
        }
    }

    /**
     * Turns the requested role name into the {@link Role} row to link.
     *
     * <p>Two separate questions are answered in order: may this flow hand out
     * the role at all ({@link #ASSIGNABLE_ROLES}), and does the role actually
     * exist ({@link RoleRepository}). Failing the second with the role's name in
     * the message is what stops an account being created with a role that can
     * never be authorised.
     */
    private Role resolveRole(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new BusinessRuleException("role is required.");
        }
        String canonical = canonicalRoleName(requested);

        if (AppConstants.ROLE_CUSTOMER.equals(canonical)) {
            throw new BusinessRuleException(
                    "Role CUSTOMER cannot be assigned here: customer logins are created by "
                    + "the customer-portal flow, not by user administration.");
        }
        if (!ASSIGNABLE_ROLES.contains(canonical)) {
            throw new BusinessRuleException(
                    "Unsupported role: \"" + requested.trim() + "\". Assignable roles are "
                    + String.join(", ", ASSIGNABLE_ROLES) + ".");
        }

        return roleRepository.findByNameIgnoreCase(canonical)
                .or(() -> resolveOwnerAlias(canonical))
                .orElseThrow(() -> new BusinessRuleException(
                        "Role \"" + canonical + "\" is not configured in the roles table."));
    }

    /**
     * Fallback lookup for the garage-owner role, tried only after the canonical
     * {@link AppConstants#ROLE_OWNER} row has been searched for and not found.
     *
     * <p>Some databases store that one role under the
     * {@link #OWNER_STORED_ALIAS} spelling, so a deployment without an
     * {@code OWNER} row would otherwise refuse to create the account at all. A
     * request that already names {@link #OWNER_STORED_ALIAS} is assignable in
     * its own right and resolves directly, so this fallback is only reached by
     * a caller asking for {@code OWNER} by its canonical name. Looking for the
     * canonical name first means a deployment that does have an {@code OWNER}
     * row keeps resolving to it, and returning {@link Optional#empty()} for
     * every other role leaves all other lookups untouched. Only <em>which
     * existing row</em> the account links to is decided here — no role is
     * created, renamed or reassigned.
     */
    private Optional<Role> resolveOwnerAlias(String canonical) {
        if (!AppConstants.ROLE_OWNER.equals(canonical)) {
            return Optional.empty();
        }
        return roleRepository.findByNameIgnoreCase(OWNER_STORED_ALIAS);
    }

    /**
     * Normalises a role name to the form stored in the {@code roles} table:
     * trimmed, upper-cased, without a {@code ROLE_} prefix, and with any run of
     * separators reduced to a single underscore.
     *
     * <p>This exists because one role arrives spelled several ways —
     * {@code SERVICE_ADVISOR}, {@code Service Advisor}, {@code service-advisor}
     * — and all of them name the same role. It deliberately does not map display
     * labels onto roles: {@code "Owner/Manager"} becomes {@code OWNER_MANAGER} and
     * is refused, because choosing between OWNER and MANAGER on a caller's behalf
     * would be a privilege decision made by a typo.
     */
    private static String canonicalRoleName(String requested) {
        String candidate = requested.trim().toUpperCase(Locale.ROOT);
        if (candidate.startsWith(ROLE_PREFIX)) {
            candidate = candidate.substring(ROLE_PREFIX.length());
        }
        return candidate.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /**
     * Maps the form's status wording onto {@code users.active}.
     *
     * <p>Omitted means {@code Active}: that is what the create-user dialog
     * pre-selects, and an account created but silently unable to sign in would
     * look like a broken invitation to whoever created it.
     */
    private Boolean resolveActive(String status) {
        if (status == null || status.isBlank()) {
            return Boolean.TRUE;
        }
        String normalised = status.trim();
        if (STATUS_ACTIVE.equalsIgnoreCase(normalised)) {
            return Boolean.TRUE;
        }
        if (STATUS_INACTIVE.equalsIgnoreCase(normalised)) {
            return Boolean.FALSE;
        }
        throw new BusinessRuleException("status must be Active or Inactive.");
    }

    private static String statusLabel(Boolean active) {
        return Boolean.TRUE.equals(active) ? STATUS_ACTIVE : STATUS_INACTIVE;
    }

    /** Required text, stored trimmed so a stray space cannot create two accounts. */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(field + " is required.");
        }
        return value.trim();
    }

    /**
     * The password as supplied.
     *
     * <p>Not trimmed, unlike the other fields: a leading or trailing space can be
     * part of a password that was typed deliberately, and removing it quietly
     * would leave a credential that does not match what its owner chose.
     * Minimum length is enforced by bean validation on the request DTO.
     */
    private String requirePassword(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("password is required.");
        }
        return value;
    }

    /** Maps a saved entity to the response — no credential field exists on it. */
    private UserResponseDTO toResponse(User e) {
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId(e.getId());
        dto.setFullName(e.getFullName());
        dto.setUsername(e.getUsername());
        dto.setEmail(e.getEmail());
        dto.setPhone(e.getPhone());
        dto.setRole(e.getRole() == null ? null : e.getRole().getName());
        dto.setActive(e.getActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}
