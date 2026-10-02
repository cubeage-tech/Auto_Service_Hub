package com.autoservicehub.service;

import com.autoservicehub.dto.AuditFilterDTO;
import com.autoservicehub.entity.AuditAction;
import com.autoservicehub.entity.AuditLog;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditServiceImpl} — attribution, redaction, transaction
 * scope and filtering (SRS 6 - Auditability).
 * Pure Mockito — no Spring context, no database.
 *
 * <p>Transaction scope is asserted by inspecting the propagation on the methods
 * themselves, because whether a row survives a rollback is a property of the
 * transaction it was written in, not of any value observable without a database.
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock AuditLogRepository repository;

    AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditServiceImpl(repository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username, "n/a", AuthorityUtils.createAuthorityList("ROLE_MANAGER")));
    }

    private AuditLog captured() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private void givenSaved() {
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
    }

    // ── Record creation and attribution ──────────────────────────────────

@Test
@DisplayName("A1 — a success entry captures the entity, action, result and details")
void a1_recordSuccess_capturesFields() {
authenticateAs("admin");
givenSaved();

service.recordSuccess("INVOICE", 42L, AuditAction.INVOICE_DELETE, "Invoice deleted");

AuditLog entry = captured();
assertThat(entry.getEntityName()).isEqualTo("INVOICE");
assertThat(entry.getEntityId()).isEqualTo("42");
assertThat(entry.getAction()).isEqualTo("INVOICE_DELETE");
assertThat(entry.getResult()).isEqualTo("SUCCESS");
assertThat(entry.getDetails()).isEqualTo("Invoice deleted");
}

@Test
@DisplayName("A2 — the actor comes from the security context, not the caller")
void a2_actor_takenFromSecurityContext() {
authenticateAs("ananya");
givenSaved();

service.recordSuccess("PAYMENT", 7L, AuditAction.PAYMENT_CREATE, "Payment recorded");

// No actor parameter exists on the method, so this cannot be spoofed.
assertThat(captured().getPerformedBy()).isEqualTo("ananya");
}

@Test
@DisplayName("A3 — work with no authentication is attributed to SYSTEM")
void a3_noAuthentication_recordedAsSystem() {
SecurityContextHolder.clearContext();
givenSaved();

service.recordSuccess("PURCHASE", 3L, AuditAction.PURCHASE_RECEIVE, "Received");

assertThat(captured().getPerformedBy()).isEqualTo(AuditLog.SYSTEM_ACTOR);
}

@Test
@DisplayName("A4 — an anonymous request is recorded as ANONYMOUS, not as a user")
void a4_anonymousRecordedAsAnonymous() {
// AnonymousAuthenticationToken reports itself as authenticated and names the
// literal principal "anonymousUser"; that must not be stored as a name.
Authentication anonymous = new AnonymousAuthenticationToken(
"key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
SecurityContextHolder.getContext().setAuthentication(anonymous);
givenSaved();

service.recordSuccess("INVOICE", 1L, AuditAction.INVOICE_CREATE, "Created");

assertThat(captured().getPerformedBy()).isEqualTo(AuditLog.ANONYMOUS_ACTOR);
}

@Test
@DisplayName("A5 — the remote address is captured when the request carries one")
void a5_ipAddress_capturedWhenAvailable() {
org.springframework.mock.web.MockHttpServletRequest request =
new org.springframework.mock.web.MockHttpServletRequest();
request.setRemoteAddr("203.0.113.42");

// A details-bearing token, built the way the JWT filter builds one: the
        // three-argument form is (principal, credentials, authorities) and is the
        // only public way to attach details to an Authentication.
        SecurityContextHolder.getContext().setAuthentication(
new UsernamePasswordAuthenticationToken(
"admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
// Re-create with details via the AbstractAuthenticationToken setter on the concrete type.
((org.springframework.security.authentication.UsernamePasswordAuthenticationToken)
SecurityContextHolder.getContext().getAuthentication())
.setDetails(new WebAuthenticationDetails(request));
givenSaved();

service.recordSuccess("SUPPLIER", 9L, AuditAction.SUPPLIER_UPDATE, "Updated");

assertThat(captured().getIpAddress()).isEqualTo("203.0.113.42");
}

@Test
@DisplayName("A6 — no address is stored when the call was not an HTTP request")
void a6_ipAddress_nullWithoutWebRequest() {
authenticateAs("admin");
givenSaved();

service.recordSuccess("SUPPLIER", 9L, AuditAction.SUPPLIER_UPDATE, "Updated");

assertThat(captured().getIpAddress()).isNull();
}

// ── Failure recording ────────────────────────────────────────────────

@Test
@DisplayName("A7 — a failure entry records FAILURE and includes the error text")
void a7_recordFailure_recordsResult() {
authenticateAs("admin");
givenSaved();

service.recordFailure("PAYMENT", 7L, AuditAction.PAYMENT_DELETE,
"Payment deleted", "Invoice already PAID");

AuditLog entry = captured();
assertThat(entry.getResult()).isEqualTo("FAILURE");
assertThat(entry.getDetails()).contains("Invoice already PAID");
}

@Test
@DisplayName("A8 — a failure with no details falls back to the error message alone")
void a8_recordFailure_fallsBackToError() {
authenticateAs("admin");
givenSaved();

service.recordFailure("INVOICE", 1L, AuditAction.INVOICE_DELETE, null, "Not allowed");

assertThat(captured().getDetails()).isEqualTo("Not allowed");
}

/**
* The distinction the whole design rests on: a success entry joins the caller's
* transaction so it rolls back with it, while a failure opens its own so it
* survives the rollback it describes.
*/
@Test
@DisplayName("A9 — success joins the caller's transaction; failure opens its own")
void a9_transactionScopes_differAsDocumented() throws Exception {
var success = AuditServiceImpl.class
.getMethod("recordSuccess", String.class, Object.class, AuditAction.class, String.class);
var failure = AuditServiceImpl.class
.getMethod("recordFailure", String.class, Object.class, AuditAction.class,
String.class, String.class);

var successTx = success.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
var failureTx = failure.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

assertThat(successTx).isNotNull();
assertThat(successTx.propagation())
.isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);

assertThat(failureTx).isNotNull();
assertThat(failureTx.propagation())
.isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
}

// ── Redaction ────────────────────────────────────────────────────────

@Test
@DisplayName("A10 — a password in the details is redacted")
void a10_passwordRedacted() {
authenticateAs("admin");
givenSaved();

service.recordSuccess("SUPPLIER", 1L, AuditAction.SUPPLIER_UPDATE,
"Contact password=Hunter2 changed");

assertThat(captured().getDetails())
.doesNotContain("Hunter2")
.contains("[REDACTED]");
}

@Test
@DisplayName("A11 — tokens, API keys and bearer credentials are redacted too")
void a11_credentialShapesRedacted() {
for (String secret : List.of(
"{\"access_token\": \"abc.def.ghi\"}",
"apiKey=sk-live-12345",
"refresh_token: r-99887",
"Authorization: Bearer eyJhbGciOi")) {

authenticateAs("admin");
givenSaved();
service.recordSuccess("INVOICE", 1L, AuditAction.INVOICE_UPDATE, secret);

String stored = captured().getDetails();
assertThat(stored)
.doesNotContain("abc.def.ghi")
.doesNotContain("sk-live-12345")
.doesNotContain("r-99887")
.doesNotContain("eyJhbGciOi");

reset(repository);
SecurityContextHolder.clearContext();
}
}

@Test
@DisplayName("A12 — scrub is the single redaction point and truncates over-long text")
void a12_scrub_truncates() {
String longText = "x".repeat(AuditServiceImpl.MAX_DETAILS_LENGTH + 200);

String scrubbed = AuditServiceImpl.scrub(longText);

assertThat(scrubbed).hasSize(AuditServiceImpl.MAX_DETAILS_LENGTH + 3);
assertThat(scrubbed).endsWith("...");
}

@Test
@DisplayName("A13 — a blank details string is stored as null, not an empty string")
void a13_blankDetails_null() {
assertThat(AuditServiceImpl.scrub("   ")).isNull();
assertThat(AuditServiceImpl.scrub(null)).isNull();
}

/**
* Audit must never break the operation it describes. Losing an audit row is bad;
* refusing a payment because the audit table was unavailable would be far worse.
*/
@Test
@DisplayName("A14 — a failing audit write does not propagate to the caller")
void a14_writeFailure_doesNotPropagate() {
authenticateAs("admin");
doThrow(new IllegalStateException("audit table unavailable"))
.when(repository).save(any(AuditLog.class));

// No exception escapes either method.
service.recordSuccess("PAYMENT", 1L, AuditAction.PAYMENT_CREATE, "Payment");
service.recordFailure("PAYMENT", 1L, AuditAction.PAYMENT_DELETE, "x", "y");
}

// ── Filtering and immutability ───────────────────────────────────────

@Test
@DisplayName("A15 — search with no filters returns everything")
void a15_search_noFiltersReturnsAll() {
AuditFilterDTO filter = new AuditFilterDTO();
when(repository.findAll(any(Specification.class), any(Pageable.class)))
.thenReturn(org.springframework.data.domain.Page.empty());

assertThat(service.search(filter, PageRequest.of(0, 20))).isEmpty();

verify(repository).findAll(any(Specification.class), any(Pageable.class));
}

@Test
@DisplayName("A16 — search maps entries to the response DTO")
void a16_search_mapsToResponse() {
AuditLog entry = new AuditLog();
entry.setId(5L);
entry.setEntityName("INVOICE");
entry.setEntityId("42");
entry.setAction("INVOICE_DELETE");
entry.setPerformedBy("admin");
entry.setResult("SUCCESS");
entry.setDetails("deleted");
entry.setIpAddress("198.51.100.7");

when(repository.findAll(any(Specification.class), any(Pageable.class)))
.thenReturn(new PageImpl<>(List.of(entry)));

var dto = service.search(new AuditFilterDTO(), PageRequest.of(0, 20))
.getContent().get(0);

assertThat(dto.getId()).isEqualTo(5L);
assertThat(dto.getEntityName()).isEqualTo("INVOICE");
assertThat(dto.getAction()).isEqualTo("INVOICE_DELETE");
assertThat(dto.getPerformedBy()).isEqualTo("admin");
assertThat(dto.getResult()).isEqualTo("SUCCESS");
assertThat(dto.getIpAddress()).isEqualTo("198.51.100.7");
}

/**
* The trail is read-only by design. Nothing in the service can update or delete
* an entry, so a record cannot be rewritten after the fact — which is the whole
* property an audit trail is for.
*/
@Test
@DisplayName("A17 — the audit service exposes no way to edit or delete an entry")
void a17_noUpdateOrDeleteApi() {
List<String> mutators = java.util.Arrays.stream(AuditServiceImpl.class.getDeclaredMethods())
.map(java.lang.reflect.Method::getName)
.filter(n -> n.toLowerCase().startsWith("delete")
|| n.toLowerCase().startsWith("update")
|| n.toLowerCase().startsWith("remove"))
.toList();

assertThat(mutators).isEmpty();
}

@Test
@DisplayName("A18 — a null entity id is stored as null, not the text 'null'")
void a18_nullEntityId_notStringified() {
authenticateAs("admin");
givenSaved();

service.recordSuccess("PURCHASE", null, AuditAction.PURCHASE_CREATE, "Created");

assertThat(captured().getEntityId()).isNull();
}

@Test
@DisplayName("A19 — a non-numeric entity id is still recorded as text")
void a19_nonNumericEntityId_supported() {
authenticateAs("admin");
givenSaved();

service.recordSuccess("PART", "BRK-PAD-01", AuditAction.STOCK_IN, "Received");

assertThat(captured().getEntityId()).isEqualTo("BRK-PAD-01");
}

@Test
@DisplayName("A20 — reading the trail never writes to it")
void a20_search_doesNotWrite() {
when(repository.findAll(any(Specification.class), any(Pageable.class)))
.thenReturn(org.springframework.data.domain.Page.empty());

service.search(new AuditFilterDTO(), PageRequest.of(0, 20));

verify(repository, never()).save(any(AuditLog.class));
verify(repository, never()).delete(any(AuditLog.class));
}
}