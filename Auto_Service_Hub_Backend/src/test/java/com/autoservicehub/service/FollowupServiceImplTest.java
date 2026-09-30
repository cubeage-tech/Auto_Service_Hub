package com.autoservicehub.service;

import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FollowupRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.impl.FollowupServiceImpl;
import com.autoservicehub.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the follow-up workflow (SRS 4.10) and for the scheduled
 * due-follow-up → notification processing.
 *
 * Test cases
 * ----------
 * FU1  create resolves customer/job card  → both linked
 * FU2  unknown customer                    → ResourceNotFoundException
 * FU3  unknown job card                    → ResourceNotFoundException
 * FU4  job card of a different customer    → BusinessRuleException
 * FU5  past due date on create             → BusinessRuleException
 * FU6  unknown status                      → BusinessRuleException
 * FU7  a blank status defaults to PENDING  → PENDING
 * FU8  update to COMPLETED is closed       → closed, not due
 * FU9  overdue follow-up reports due       → due, not closed
 * FU10 reopening a notified follow-up      → notified stamp cleared
 * FU11 the due query excludes closed follow-ups
 * FU12 listing by an unknown customer      → ResourceNotFoundException
 *
 * NS1  due follow-up notified              → one notification per recipient
 * NS2  a second run is a no-op             → nothing new created
 * NS3  an already-stamped follow-up        → skipped
 * NS4  duplicate notification suppressed   → only the new one is counted
 * NS5  message carries no customer PII     → ids only
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FollowupServiceImplTest {

    @Mock FollowupRepository       repository;
    @Mock CustomerRepository      customerRepository;
    @Mock JobCardRepository       jobCardRepository;
    @Mock UserRepository          userRepository;
    @Mock NotificationServiceImpl notificationService;

    @InjectMocks FollowupServiceImpl service;

    private static final LocalDate TODAY = LocalDate.now();

    // ── Fixtures ───────────────────────────────────────────────────────────

    private Customer customer(Long id) {
        Customer c = new Customer();
        c.setId(id);
        c.setName("Customer " + id);
        c.setPhone("+9198" + id + "00000");
        return c;
    }

    private JobCard jobCard(Long id, Long customerId) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setJobCardNumber("JC-" + id);
        jc.setStatus("DELIVERED");
        if (customerId != null) {
            jc.setCustomer(customer(customerId));
        }
        return jc;
    }

    private Followup followup(Long id, String status, LocalDate dueDate, boolean notified) {
        Followup f = new Followup();
        f.setId(id);
        f.setCustomer(customer(1L));
        f.setStatus(status);
        f.setDueDate(dueDate);
        f.setReason("Service review");
        if (notified) {
            f.setNotifiedAt(LocalDateTime.now().minusDays(1));
        }
        return f;
    }

    private FollowupRequestDTO request(Long customerId, Long jobCardId,
                                       LocalDate dueDate, String status) {
        FollowupRequestDTO r = new FollowupRequestDTO();
        r.setCustomerId(customerId);
        r.setJobCardId(jobCardId);
        r.setDueDate(dueDate);
        r.setReason("Service review");
        r.setStatus(status);
        return r;
    }

    /** Stubs the scheduler's "due and not yet notified" query as returning one row. */
    private void givenOneUnNotified(String status, LocalDate dueDate) {
        when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                anyList(), eq(TODAY)))
                .thenReturn(List.of(followup(1L, status, dueDate, false)));
    }

    /** Saves whatever it is handed back with its id set, as JPA would. */
    private void givenSaved() {
        when(repository.save(any(Followup.class))).thenAnswer(inv -> {
            Followup f = inv.getArgument(0);
            if (f.getId() == null) {
                f.setId(1L);
            }
            return f;
        });
    }

    private User recipient(Long id) {
        Role role = new Role();
        role.setName("SERVICE_ADVISOR");
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        u.setRole(role);
        u.setActive(true);
        return u;
    }

    // ── Creation and validation ────────────────────────────────────────────

    @Nested
    @DisplayName("Creation and validation")
    class Creation {

        @Test
        @DisplayName("FU1 create resolves the customer and job card")
        void createsAndResolvesReferences() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(jobCardRepository.findById(10L)).thenReturn(Optional.of(jobCard(10L, 1L)));
            givenSaved();

            FollowupResponseDTO dto = service.create(request(1L, 10L, TODAY.plusDays(7), null));

            assertThat(dto.getId()).isEqualTo(1L);
            assertThat(dto.getCustomerId()).isEqualTo(1L);
            assertThat(dto.getJobCardId()).isEqualTo(10L);
            assertThat(dto.getJobCardNumber()).isEqualTo("JC-10");
            assertThat(dto.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("FU2 an unknown customer is rejected")
        void rejectsUnknownCustomer() {
            when(customerRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request(99L, null, TODAY.plusDays(1), null)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("FU3 an unknown job card is rejected")
        void rejectsUnknownJobCard() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(jobCardRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(request(1L, 404L, TODAY.plusDays(1), null)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("FU4 a job card belonging to another customer is rejected")
        void rejectsJobCardOfAnotherCustomer() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(jobCardRepository.findById(20L)).thenReturn(Optional.of(jobCard(20L, 2L)));

            assertThatThrownBy(() -> service.create(request(1L, 20L, TODAY.plusDays(1), null)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("different customer");
        }

        @Test
        @DisplayName("FU5 a new follow-up cannot be dated in the past")
        void rejectsPastDueDateOnCreate() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

            assertThatThrownBy(() -> service.create(request(1L, null, TODAY.minusDays(1), null)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("in the past");
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("FU6 an unsupported status is rejected")
        void rejectsUnknownStatus() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

            assertThatThrownBy(() -> service.create(request(1L, null, TODAY.plusDays(1), "WAT")))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("Unsupported follow-up status");
        }

        @Test
        @DisplayName("FU7 a blank status defaults to PENDING")
        void defaultsStatusToPending() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            givenSaved();

            assertThat(service.create(request(1L, null, TODAY.plusDays(1), "  ")).getStatus())
                    .isEqualTo("PENDING");
        }
    }

    // ── Status handling and due detection ──────────────────────────────────

    @Nested
    @DisplayName("Status handling and due detection")
    class StatusHandling {

        @Test
        @DisplayName("FU8 a COMPLETED follow-up is closed and not due")
        void completedIsClosedAndNotDue() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(repository.findById(5L))
                    .thenReturn(Optional.of(followup(5L, "PENDING", TODAY.minusDays(3), false)));
            givenSaved();

            FollowupResponseDTO dto =
                    service.update(5L, request(1L, null, TODAY.minusDays(3), "COMPLETED"));

            assertThat(dto.getStatus()).isEqualTo("COMPLETED");
            assertThat(dto.isClosed()).isTrue();
            assertThat(dto.isDue()).isFalse();
        }

        @Test
        @DisplayName("FU9 an overdue open follow-up reports due")
        void overdueOpenFollowupIsDue() {
            when(repository.findById(6L))
                    .thenReturn(Optional.of(followup(6L, "IN_PROGRESS", TODAY.minusDays(2), false)));

            FollowupResponseDTO dto = service.getById(6L);

            assertThat(dto.isDue()).isTrue();
            assertThat(dto.isClosed()).isFalse();
        }

        @Test
        @DisplayName("a follow-up due in the future is not yet due")
        void futureFollowupIsNotDue() {
            when(repository.findById(7L))
                    .thenReturn(Optional.of(followup(7L, "PENDING", TODAY.plusDays(1), false)));

            assertThat(service.getById(7L).isDue()).isFalse();
        }

        @Test
        @DisplayName("a follow-up due exactly today counts as due")
        void dueTodayCountsAsDue() {
            when(repository.findById(70L))
                    .thenReturn(Optional.of(followup(70L, "PENDING", TODAY, false)));

            assertThat(service.getById(70L).isDue()).isTrue();
        }

        @Test
        @DisplayName("FU10 reopening a notified follow-up clears the notified stamp")
        void reopeningClearsNotifiedStamp() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(repository.findById(8L))
                    .thenReturn(Optional.of(followup(8L, "COMPLETED", TODAY.minusDays(1), true)));
            givenSaved();

            FollowupResponseDTO dto =
                    service.update(8L, request(1L, null, TODAY.minusDays(1), "PENDING"));

            assertThat(dto.getStatus()).isEqualTo("PENDING");
            assertThat(dto.getNotifiedAt()).isNull();
        }

        @Test
        @DisplayName("an update of a still-notified follow-up keeps the stamp")
        void updatingAnOpenNotifiedFollowupKeepsStamp() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(repository.findById(81L))
                    .thenReturn(Optional.of(followup(81L, "PENDING", TODAY.plusDays(2), true)));
            givenSaved();

            FollowupResponseDTO dto =
                    service.update(81L, request(1L, null, TODAY.plusDays(2), "PENDING"));

            assertThat(dto.getNotifiedAt()).isNotNull();
        }

        // ── The stamp is cleared ONLY on a CLOSED → OPEN transition ─────────
        //
        // | previous | new      | notifiedAt |
        // |----------|----------|------------|
        // | CLOSED   | OPEN     | cleared    |
        // | OPEN     | CLOSED   | kept       |
        // | OPEN     | OPEN     | kept       |
        // | CLOSED   | CLOSED   | kept       |
        //
        // Anything looser would let a routine edit of an already-notified
        // follow-up re-arm it, and the nightly job would notify the customer a
        // second time for the same reminder.

        @Test
        @DisplayName("CLOSED → OPEN clears the notified stamp")
        void closedToOpenClearsStamp() {
            assertStampAfterUpdate("COMPLETED", "PENDING", null);
        }

        @Test
        @DisplayName("CANCELLED → OPEN clears the notified stamp")
        void cancelledToOpenClearsStamp() {
            assertStampAfterUpdate("CANCELLED", "IN_PROGRESS", null);
        }

        @Test
        @DisplayName("OPEN → CLOSED keeps the notified stamp")
        void openToClosedKeepsStamp() {
            assertStampAfterUpdate("PENDING", "COMPLETED", true);
            assertStampAfterUpdate("IN_PROGRESS", "CANCELLED", true);
        }

        @Test
        @DisplayName("OPEN → OPEN keeps the notified stamp")
        void openToOpenKeepsStamp() {
            assertStampAfterUpdate("PENDING", "PENDING", true);
            assertStampAfterUpdate("PENDING", "IN_PROGRESS", true);
        }

        @Test
        @DisplayName("CLOSED → CLOSED keeps the notified stamp")
        void closedToClosedKeepsStamp() {
            assertStampAfterUpdate("COMPLETED", "CANCELLED", true);
            assertStampAfterUpdate("COMPLETED", "COMPLETED", true);
        }

        /**
         * Updates an already-notified follow-up from one status to another and
         * asserts whether the stamp survived.
         *
         * @param expectCleared true when the stamp is expected to be gone after
         *                      the update; the follow-up always starts notified
         */
        private void assertStampAfterUpdate(String previousStatus, String newStatus,
                                           Boolean expectCleared) {
            Followup existing = followup(90L, previousStatus, TODAY, true);
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            when(repository.findById(90L)).thenReturn(Optional.of(existing));
            givenSaved();

            FollowupResponseDTO dto = service.update(90L, request(1L, null, TODAY, newStatus));

            assertThat(dto.getStatus()).isEqualTo(newStatus);
            if (Boolean.TRUE.equals(expectCleared)) {
                assertThat(dto.getNotifiedAt())
                        .as("stamp after %s -> %s", previousStatus, newStatus)
                        .isNotNull();
            } else {
                assertThat(dto.getNotifiedAt())
                        .as("stamp after %s -> %s", previousStatus, newStatus)
                        .isNull();
            }
        }

        @Test
        @DisplayName("a status is normalised to upper case")
        void statusIsNormalised() {
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
            givenSaved();

            assertThat(service.create(request(1L, null, TODAY.plusDays(1), "completed")).getStatus())
                    .isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("FU11 the due query only considers open follow-ups")
        void dueQueryExcludesClosed() {
            when(repository.findByStatusInAndDueDateLessThanEqualOrderByIdAsc(anyList(), eq(TODAY)))
                    .thenReturn(List.of(followup(1L, "PENDING", TODAY.minusDays(1), false)));

            assertThat(service.listDue(TODAY, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
            verify(repository).findByStatusInAndDueDateLessThanEqualOrderByIdAsc(
                    FollowupServiceImpl.OPEN_STATUSES, TODAY);
        }

        @Test
        @DisplayName("the pending query returns open follow-ups only")
        void pendingQueryReturnsOpen() {
            when(repository.findByStatusInOrderByDueDateAscIdAsc(anyList()))
                    .thenReturn(List.of(followup(1L, "PENDING", TODAY, false),
                                         followup(2L, "IN_PROGRESS", TODAY, false)));

            assertThat(service.listPending(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("FU12 listing by an unknown customer is a 404")
        void listByUnknownCustomerRejected() {
            when(customerRepository.existsById(77L)).thenReturn(false);

            assertThatThrownBy(() -> service.listByCustomer(77L, PageRequest.of(0, 10)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("listing by a known customer returns their follow-ups")
        void listByCustomerReturnsFollowups() {
            when(customerRepository.existsById(1L)).thenReturn(true);
            when(repository.findByCustomerIdOrderByDueDateDesc(1L))
                    .thenReturn(List.of(followup(1L, "PENDING", TODAY, false)));

            assertThat(service.listByCustomer(1L, PageRequest.of(0, 10))).hasSize(1);
        }

        @Test
        @DisplayName("reading an unknown follow-up is a 404")
        void getUnknownIsRejected() {
            when(repository.findById(3L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(3L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("deleting an unknown follow-up is a 404")
        void deleteUnknownIsRejected() {
            when(repository.existsById(3L)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(3L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Due follow-up → notification processing ────────────────────────────

    @Nested
    @DisplayName("Due follow-up processing")
    class DueProcessing {

        @Test
        @DisplayName("NS1 a due follow-up notifies every active recipient")
        void notifiesRecipientsForDueFollowup() {
            Followup f = followup(1L, "PENDING", TODAY, false);
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(f));
            when(userRepository.findActiveByRoleNameIn(anyList()))
                    .thenReturn(List.of(recipient(1L), recipient(2L)));
            when(notificationService.notifyUserOnce(anyLong(), any(), any(), any(), any()))
                    .thenReturn(true);

            assertThat(service.notifyDueFollowUps(TODAY)).isEqualTo(2);
            verify(notificationService, times(2))
                    .notifyUserOnce(anyLong(), any(), any(), any(), any());
            assertThat(f.getNotifiedAt()).isNotNull();
            verify(repository).saveAll(anyList());
        }

        @Test
        @DisplayName("NS2 a second run over the same follow-up creates nothing further")
        void secondRunIsANoOp() {
            // The stamp set by the first run means the query no longer matches.
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of());

            assertThat(service.notifyDueFollowUps(TODAY)).isZero();
            verify(notificationService, never()).notifyUserOnce(anyLong(), any(), any(), any(), any());
            verify(repository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("NS3 a follow-up with no matching row is left alone")
        void alreadyProcessedFollowupIsSkipped() {
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of());

            assertThat(service.notifyDueFollowUps(TODAY)).isZero();
            verify(notificationService, never())
                    .notifyUserOnce(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("NS4 a notification the recipient already has is not counted twice")
        void duplicateNotificationIsSuppressed() {
            Followup f = followup(1L, "PENDING", TODAY, false);
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(f));
            when(userRepository.findActiveByRoleNameIn(anyList()))
                    .thenReturn(List.of(recipient(1L), recipient(2L)));
            // Recipient 1 already has one for this follow-up; recipient 2 does not.
            when(notificationService.notifyUserOnce(eq(1L), any(), any(), any(), any()))
                    .thenReturn(false);
            when(notificationService.notifyUserOnce(eq(2L), any(), any(), any(), any()))
                    .thenReturn(true);

            assertThat(service.notifyDueFollowUps(TODAY)).isEqualTo(1);
            assertThat(f.getNotifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("no eligible recipients means nothing is sent, but the follow-up is stamped")
        void noRecipientsStillStamps() {
            Followup f = followup(1L, "PENDING", TODAY, false);
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(f));
            when(userRepository.findActiveByRoleNameIn(anyList())).thenReturn(List.of());

            assertThat(service.notifyDueFollowUps(TODAY)).isZero();
            verify(notificationService, never()).notifyUserOnce(anyLong(), any(), any(), any(), any());
            assertThat(f.getNotifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("NS5 the notification identifies the follow-up without leaking customer PII")
        void notificationCarriesReferenceButNoPii() {
            Followup f = followup(42L, "PENDING", TODAY.minusDays(1), false);
            f.setJobCard(jobCard(11L, 1L));
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(f));
            when(userRepository.findActiveByRoleNameIn(anyList())).thenReturn(List.of(recipient(1L)));
            when(notificationService.notifyUserOnce(anyLong(), any(), any(), any(), any()))
                    .thenReturn(true);

            service.notifyDueFollowUps(TODAY);

            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(notificationService).notifyUserOnce(
                    eq(1L), title.capture(), message.capture(),
                    eq(NotificationServiceImpl.REFERENCE_FOLLOWUP), eq(42L));

            // Enough to identify the follow-up and the job card.
            assertThat(message.getValue()).contains("42").contains("11").contains("overdue");
            // No customer name or phone number copied in.
            assertThat(message.getValue())
                    .doesNotContain("Customer 1")
                    .doesNotContain("+919");
            assertThat(title.getValue()).isNotBlank();
        }

        @Test
        @DisplayName("a very long reason is truncated in the title")
        void longReasonIsTruncated() {
            Followup f = followup(1L, "PENDING", TODAY, false);
            f.setReason("x".repeat(300));
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(f));
            when(userRepository.findActiveByRoleNameIn(anyList())).thenReturn(List.of(recipient(1L)));
            when(notificationService.notifyUserOnce(anyLong(), any(), any(), any(), any()))
                    .thenReturn(true);

            service.notifyDueFollowUps(TODAY);

            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            verify(notificationService).notifyUserOnce(eq(1L), title.capture(), any(), any(), eq(1L));
            assertThat(title.getValue().length()).isLessThan(100);
        }

        @Test
        @DisplayName("an empty due set performs no writes at all")
        void emptySetIsSafe() {
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of());

            assertThat(service.notifyDueFollowUps(TODAY)).isZero();
            verify(repository, never()).saveAll(any());
        }

        @Test
        @DisplayName("only open follow-ups are selected by the scheduler query")
        void selectsOnlyOpenFollowups() {
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of());

            service.notifyDueFollowUps(TODAY);

            verify(repository).findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    FollowupServiceImpl.OPEN_STATUSES, TODAY);
        }

        @Test
        @DisplayName("several due follow-ups are each notified and stamped")
        void severalDueFollowupsAreAllProcessed() {
            Followup a = followup(1L, "PENDING", TODAY, false);
            Followup b = followup(2L, "IN_PROGRESS", TODAY.minusDays(5), false);
            when(repository.findByStatusInAndDueDateLessThanEqualAndNotifiedAtIsNullOrderByIdAsc(
                    anyList(), eq(TODAY))).thenReturn(List.of(a, b));
            when(userRepository.findActiveByRoleNameIn(anyList())).thenReturn(List.of(recipient(1L)));
            when(notificationService.notifyUserOnce(anyLong(), any(), any(), any(), any()))
                    .thenReturn(true);

            assertThat(service.notifyDueFollowUps(TODAY)).isEqualTo(2);
            assertThat(a.getNotifiedAt()).isNotNull();
            assertThat(b.getNotifiedAt()).isNotNull();
            verify(notificationService).notifyUserOnce(anyLong(), any(), any(), any(), eq(1L));
            verify(notificationService).notifyUserOnce(anyLong(), any(), any(), any(), eq(2L));
        }
    }

    // APPENDED_SECTIONS
}
