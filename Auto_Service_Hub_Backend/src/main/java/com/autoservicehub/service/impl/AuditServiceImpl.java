package com.autoservicehub.service.impl;

import com.autoservicehub.dto.AuditFilterDTO;
import com.autoservicehub.dto.AuditLogResponseDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.AuditLog;
import com.autoservicehub.entity.AuditResult;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.service.AuditService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The single writer and reader of the audit trail (SRS 6 - Auditability).
 *
 * <p>Three things live here and nowhere else, because each is easy to get
 * subtly wrong when repeated across modules:
 *
 * <ol>
 *   <li><b>Attribution.</b> The actor comes from the security context. A caller
 *       cannot name someone else, so an entry always names whoever was actually
 *       signed in.</li>
 *   <li><b>Redaction.</b> Detail strings are scrubbed for credential-shaped text
 *       before they are stored. {@code details} is free text, so a caller could
 *       paste a request body into it; without scrubbing, one careless call site
 *       would write a password to disk permanently.</li>
 *   <li><b>Transaction scope.</b> Success joins the caller's transaction,
 *       failure does not. See {@link AuditService}.</li>
 * </ol>
 *
 * <p>Audit is also never allowed to break the operation it describes: a write
 * failure is logged and swallowed. Losing an audit row is bad; refusing a payment
 * because the audit table was unavailable would be far worse.
 */
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    /**
     * Upper bound on a details string.
     *
     * <p>Enough for a sentence describing an action, small enough that the column
     * cannot be used to store a payload.
     */
    public static final int MAX_DETAILS_LENGTH = 500;

    /**
     * Credential-bearing text, redacted whole — key <em>and</em> value.
     *
     * <p>Deliberately broad. It matches on the <em>name</em> of a secret-bearing
     * field rather than trying to recognise a token's shape, so
     * {@code password=hunter2}, {@code "token": "..."}, {@code apiKey: ...} and
     * {@code Authorization: Bearer ...} are all caught even when the value is
     * unfamiliar.
     *
     * <p>The value is consumed as part of the match, not left behind after the
     * key is replaced. Masking only the name would turn
     * {@code password=hunter2} into {@code [REDACTED]=hunter2} — which looks
     * redacted and is not. The value stops at a quote, comma, semicolon, closing
     * brace or whitespace, so surrounding punctuation survives.
     */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization"
            + "|refresh[_-]?token|access[_-]?token|credential|private[_-]?key)\\b"
            // An optional closing quote, so a JSON key ("token": "x") matches as
            // readily as a form field (password=x).
            + "[\"']?\\s*[:=]\\s*[\"']?"
            // "Authorization: Bearer eyJ..." — the scheme is part of the secret and
            // must be consumed with it, or the token alone survives redaction.
            + "(?:bearer\\s+)?"
            + "[^\\s\"',;}]+"
            // A bare "Bearer <token>" with no key in front of it.
            + "|\\bbearer\\s+[A-Za-z0-9._~+/=-]+");

    /** What a redacted value is replaced with. */
    private static final String REDACTED = "[REDACTED]";

    private final AuditLogRepository repository;

    // ── Writing ──────────────────────────────────────────────────────────

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(String entityName, Object entityId, AuditAction action, String details) {
        persist(entityName, entityId, action, details, null, AuditResult.SUCCESS);
    }

    /**
     * Recorded in its own transaction on purpose.
     *
     * <p>{@code REQUIRES_NEW} suspends the caller's transaction, so the row commits
     * independently and survives the rollback of the operation it describes —
     * which is precisely when a failure record matters most.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String entityName, Object entityId, AuditAction action,
                              String details, String errorMessage) {
        String combined = (details == null || details.isBlank())
                ? errorMessage
                : details + " | error: " + errorMessage;
        persist(entityName, entityId, action, combined, errorMessage, AuditResult.FAILURE);
    }

    /**
     * Writes one row, containing every way this can fail.
     *
     * <p>An audit problem must never propagate into the business operation: a full
     * disk or a schema mismatch here would otherwise turn every payment into an
     * error. So the write is attempted, and any failure logged for an operator.
     */
    private void persist(String entityName, Object entityId, AuditAction action,
                         String details, String errorMessage, AuditResult result) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEntityName(entityName);
            entry.setEntityId(entityId == null ? null : String.valueOf(entityId));
            entry.setAction(action == null ? null : action.name());
            entry.setPerformedBy(currentActor());
            entry.setIpAddress(currentRequestIp());
            entry.setDetails(scrub(details));
            entry.setResult(result.name());

            repository.save(entry);
        } catch (RuntimeException ex) {
            log.error("Failed to write audit entry action={} entity={}/{} result={}: {}",
                    action, entityName, entityId, result, ex.getMessage());
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponseDTO> search(AuditFilterDTO filter, Pageable pageable) {
        return repository.findAll(toSpecification(filter), pageable).map(this::toResponse);
    }

    /**
     * Builds the query from whichever filters were supplied.
     *
     * <p>Every clause is guarded, so an absent filter contributes nothing rather
     * than matching null — which is the difference between "all entries" and
     * "entries with no action".
     */
    private Specification<AuditLog> toSpecification(AuditFilterDTO f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (f.getEntityName() != null && !f.getEntityName().isBlank()) {
                predicates.add(cb.equal(
                        cb.lower(root.get("entityName")), f.getEntityName().trim().toLowerCase()));
            }
            if (f.getEntityId() != null && !f.getEntityId().isBlank()) {
                predicates.add(cb.equal(root.get("entityId"), f.getEntityId().trim()));
            }
            if (f.getAction() != null && !f.getAction().isBlank()) {
                predicates.add(cb.equal(root.get("action"), f.getAction().trim().toUpperCase()));
            }
            if (f.getResult() != null && !f.getResult().isBlank()) {
                predicates.add(cb.equal(root.get("result"), f.getResult().trim().toUpperCase()));
            }
            if (f.getPerformedBy() != null && !f.getPerformedBy().isBlank()) {
                predicates.add(cb.equal(root.get("performedBy"), f.getPerformedBy().trim()));
            }
            if (f.getFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.getFrom()));
            }
            // Half-open [from, to), matching the project's other report queries.
            if (f.getTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), f.getTo()));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AuditLogResponseDTO toResponse(AuditLog entry) {
        AuditLogResponseDTO dto = new AuditLogResponseDTO();
        dto.setId(entry.getId());
        dto.setEntityName(entry.getEntityName());
        dto.setEntityId(entry.getEntityId());
        dto.setAction(entry.getAction());
        dto.setPerformedBy(entry.getPerformedBy());
        dto.setResult(entry.getResult());
        dto.setIpAddress(entry.getIpAddress());
        dto.setDetails(entry.getDetails());
        dto.setTimestamp(entry.getCreatedAt());
        return dto;
    }

    // ── Attribution ──────────────────────────────────────────────────────

    /**
     * Who is acting, from the security context only.
     *
     * <p>{@code AnonymousAuthenticationToken} reports itself as authenticated and
     * names the literal principal {@code anonymousUser}, which would otherwise be
     * recorded as though it were a real account — so it is recognised here and
     * recorded as {@link AuditLog#ANONYMOUS_ACTOR}.
     *
     * <p>No authentication at all means an internal caller such as a scheduler,
     * recorded as {@link AuditLog#SYSTEM_ACTOR}.
     */
    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            return AuditLog.SYSTEM_ACTOR;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return AuditLog.ANONYMOUS_ACTOR;
        }
        return name;
    }

    /**
     * The caller's remote address, when one is safely available.
     *
     * <p>Read from Spring Security's {@code WebAuthenticationDetails}, which the
     * JWT filter populates for real requests. Null for schedulers, tests and
     * internal calls, rather than an invented address.
     */
    private String currentRequestIp() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof WebAuthenticationDetails details)) {
            return null;
        }
        String address = details.getRemoteAddress();
        return address == null || address.isBlank() ? null : address;
    }

    // ── Redaction ────────────────────────────────────────────────────────

    /**
     * Removes credential-bearing text and caps the length.
     *
     * <p>Runs on every details string, whatever the caller wrote, because the
     * whole guarantee rests on there being exactly one place this is enforced.
     *
     * <p>Public so the guarantee can be tested directly rather than inferred from
     * a caller's behaviour.
     */
    public static String scrub(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = SENSITIVE_FIELD.matcher(value).replaceAll(REDACTED);
        if (cleaned.length() > MAX_DETAILS_LENGTH) {
            cleaned = cleaned.substring(0, MAX_DETAILS_LENGTH) + "...";
        }
        return cleaned;
    }
}