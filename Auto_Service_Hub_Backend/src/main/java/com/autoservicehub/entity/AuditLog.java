package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to the 'audit_logs' table (SRS section 8.2 High-Level Entities).
 *
 * <p>An immutable record that one important business action happened: who did it,
 * what they did, to which record, whether it worked, and when. Written by
 * {@code AuditService} and never edited afterwards — an audit trail that can be
 * rewritten is not evidence of anything.
 *
 * <p><b>What is deliberately not here.</b> There is no password, token, API key
 * or payment credential column, and {@code AuditService} scrubs detail strings
 * before they reach {@link #details}. Recording a secret would turn this table
 * into the single most attractive target in the database, so the rule is that a
 * caller describes <em>what happened</em>, never <em>with what secret</em>.
 *
 * <p>The timestamp comes from {@link BaseEntity#createdAt}, which is stamped at
 * persist time rather than supplied, so a caller cannot backdate an entry.
 *
 * <p>Indexes cover the four filters an auditor actually uses: which record, what
 * happened to it, who did it, and in what window.
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity_name",  columnList = "entity_name"),
        @Index(name = "idx_audit_action",       columnList = "action"),
        @Index(name = "idx_audit_performed_by", columnList = "performed_by"),
        @Index(name = "idx_audit_created_at",   columnList = "created_at")
})
public class AuditLog extends BaseEntity {

    /** The kind of record acted on, e.g. {@code JOB_TASK} or {@code INVOICE}. */
    @Column(name = "entity_name")
    private String entityName;

    /** The id of the record acted on. A String so non-numeric ids still fit. */
    @Column(name = "entity_id")
    private String entityId;

    /** What was done — see {@code AuditAction}. */
    @Column(name = "action")
    private String action;

    /**
     * Username of whoever did it, taken from the security context and never from
     * the request body.
     *
     * <p>A username rather than a foreign key, matching the column this entity
     * already declared: an audit trail must survive the user record being
     * renamed or removed, or the history of an action would vanish with it.
     *
     * <p>{@link #SYSTEM_ACTOR} for scheduled and internal work with no signed-in
     * user, {@link #ANONYMOUS_ACTOR} for a request that reached the service with
     * no authentication at all.
     */
    @Column(name = "performed_by")
    private String performedBy;

    /** SUCCESS or FAILURE — see {@code AuditResult}. */
    @Column(name = "result", length = 20)
    private String result;

    /**
     * Remote address the action came from, when one is safely available.
     *
     * <p>Nullable on purpose. It is resolved from Spring Security's
     * {@code WebAuthenticationDetails}, which is only populated for a real HTTP
     * request; schedulers, tests and internal calls have no address and store
     * null rather than a fabricated one.
     */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /**
     * A short, already-scrubbed description of what happened.
     *
     * <p>Free text and never parsed back. {@code AuditService} truncates and
     * redacts it, so a caller cannot accidentally write a whole request body —
     * or a secret — into it.
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /** Actor name for work with no signed-in user (schedulers, migrations). */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    /** Actor name for a request that arrived with no authentication. */
    public static final String ANONYMOUS_ACTOR = "ANONYMOUS";
}
