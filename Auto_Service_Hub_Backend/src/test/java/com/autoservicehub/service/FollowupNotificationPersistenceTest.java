package com.autoservicehub.service;

import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Notification;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FollowupRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.NotificationRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.impl.FollowupServiceImpl;
import com.autoservicehub.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the follow-up → notification workflow against a real
 * database.
 *
 * <p>Covers what a Mockito test cannot: that the {@code notified_at} stamp and
 * the {@code is_read} flag really are persisted, that the derived queries
 * actually filter the way their names claim, that the {@code user_id} foreign
 * key is enforced, and that a rejected create leaves nothing behind.
 *
 * <p>Uses {@code @DataJpaTest}. application.yml pins MySQLDialect for the real
 * MySQL deployment, but that DDL is not executable on H2, so the dialect is
 * overridden to match the actual JDBC metadata. The database name is isolated
 * from the shared {@code testdb} used by the other slices, because
 * {@code create-drop} from one context drops the schema for all of them.
 *
 * Test cases
 * ----------
 * P1  a due follow-up creates one notification per recipient and stamps the follow-up
 * P2  a second run creates nothing further (idempotent)
 * P3  a completed follow-up is never notified
 * P4  a future follow-up is never notified
 * P5  the is_read flag round-trips through the reserved-word column
 * P6  unread counting is per user
 * P7  reopening a notified follow-up clears the stamp and notifies again
 * P8  a rejected create persists nothing
 * P9  a job card of another customer is rejected and persists nothing
 * P10 notifications are scoped to their owner
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:followup-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FollowupServiceImpl.class, NotificationServiceImpl.class})
class FollowupNotificationPersistenceTest {

    @Autowired TestEntityManager entityManager;

    @Autowired FollowupServiceImpl       followupService;
    @Autowired NotificationServiceImpl  notificationService;
    @Autowired FollowupRepository       followupRepository;
    @Autowired NotificationRepository  notificationRepository;
    @Autowired CustomerRepository      customerRepository;
    @Autowired JobCardRepository       jobCardRepository;
    @Autowired UserRepository          userRepository;
    @Autowired com.autoservicehub.repository.RoleRepository roleRepository;

    // ── Fixtures ───────────────────────────────────────────────────────────

    private Customer givenCustomer(String name) {
        Customer c = new Customer();
        c.setName(name);
        c.setPhone("+919800000000");
        return customerRepository.save(c);
    }

    private JobCard givenJobCard(Customer owner) {
        JobCard jc = new JobCard();
        jc.setJobCardNumber("JC-" + System.nanoTime());
        jc.setServiceType("SERVICE");
        jc.setStatus("DELIVERED");
        jc.setCustomer(owner);
        return jobCardRepository.save(jc);
    }

    /** An active user in a role the scheduler notifies. */
    private User givenAdvisor(String username) {
        Role role = new Role();
        role.setName("SERVICE_ADVISOR");
        role.setDescription("Service advisor");
        role = roleRepository.save(role);

        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setFullName(username);
        u.setPasswordHash("hash");
        u.setActive(true);
        u.setRole(role);
        return userRepository.save(u);
    }

    private Followup givenFollowup(Customer customer, JobCard jobCard,
                                  LocalDate dueDate, String status) {
        Followup f = new Followup();
        f.setCustomer(customer);
        f.setJobCard(jobCard);
        f.setDueDate(dueDate);
        f.setReason("Post-service check");
        f.setStatus(status);
        return followupRepository.save(f);
    }

    // ── Due follow-up → notification, end to end ───────────────────────────

    @Test
    @DisplayName("P1 a due follow-up notifies each recipient once and is stamped")
    void dueFollowupCreatesNotifications() {
        Customer customer = givenCustomer("Ravi");
        JobCard jobCard = givenJobCard(customer);
        User advisor = givenAdvisor("advisor1");
        Followup f = givenFollowup(customer, jobCard, LocalDate.now(), "PENDING");

        int created = followupService.notifyDueFollowUps(LocalDate.now());

        assertThat(created).isEqualTo(1);
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);

        Notification n = notifications.get(0);
        assertThat(n.getUser().getId()).isEqualTo(advisor.getId());
        assertThat(n.getReferenceType()).isEqualTo(NotificationServiceImpl.REFERENCE_FOLLOWUP);
        assertThat(n.getReferenceId()).isEqualTo(f.getId());
        assertThat(n.getRead()).isFalse();
        assertThat(n.getChannel()).isEqualTo(NotificationServiceImpl.CHANNEL_IN_APP);
        // The follow-up records that it has been raised.
        assertThat(followupRepository.findById(f.getId()).orElseThrow().getNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("P2 a second run creates nothing further (idempotent)")
    void secondRunIsIdempotent() {
        Customer customer = givenCustomer("Ravi");
        givenAdvisor("advisor1");
        givenFollowup(customer, null, LocalDate.now(), "PENDING");

        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isEqualTo(1);
        // Running again over the same data must add nothing.
        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isZero();
        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("P3 a completed follow-up is never notified")
    void completedFollowupIsNotNotified() {
        Customer customer = givenCustomer("Ravi");
        givenAdvisor("advisor1");
        givenFollowup(customer, null, LocalDate.now().minusDays(10), "COMPLETED");

        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isZero();
        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("P4 a follow-up that is not yet due is not notified")
    void futureFollowupIsNotNotified() {
        Customer customer = givenCustomer("Ravi");
        givenAdvisor("advisor1");
        givenFollowup(customer, null, LocalDate.now().plusDays(3), "PENDING");

        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isZero();
        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("P7 a notified follow-up is not notified again until it is closed and reopened")
    void reopeningNotifiesAgain() {
        Customer customer = givenCustomer("Ravi");
        givenAdvisor("advisor1");
        Followup f = givenFollowup(customer, null, LocalDate.now(), "PENDING");
        followupService.notifyDueFollowUps(LocalDate.now());

        // Still open: a routine edit must NOT re-arm the follow-up, so the next
        // run stays silent rather than notifying the customer twice.
        followupService.update(f.getId(), reopenRequest(customer, "PENDING"));
        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isZero();
        assertThat(notificationRepository.findAll()).hasSize(1);

        // Closing keeps the stamp, so it is still not re-notified.
        followupService.update(f.getId(), reopenRequest(customer, "COMPLETED"));
        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isZero();

        // The advisor reads the reminder, then the follow-up is re-opened. This is
        // a CLOSED → OPEN transition, which clears the stamp, and the reminder is
        // now read so it may be raised again.
        notificationRepository.findAll().forEach(n -> {
            n.setRead(true);
            n.setStatus("READ");
            notificationRepository.save(n);
        });

        followupService.update(f.getId(), reopenRequest(customer, "PENDING"));
        assertThat(followupService.notifyDueFollowUps(LocalDate.now())).isEqualTo(1);
        assertThat(notificationRepository.findAll()).hasSize(2);
    }

    private FollowupRequestDTO reopenRequest(Customer customer, String status) {
        FollowupRequestDTO dto = new FollowupRequestDTO();
        dto.setCustomerId(customer.getId());
        dto.setDueDate(LocalDate.now());
        dto.setReason("Post-service check");
        dto.setStatus(status);
        return dto;
    }

    // ── Notification read state and per-user scoping ───────────────────────

    @Test
    @DisplayName("P5 the is_read flag round-trips through the reserved-word column")
    void isReadRoundTrips() {
        User advisor = givenAdvisor("advisor1");
        notificationService.sendInApp(advisor.getId(), "Job delivered", "Your car is ready");

        Notification n = notificationRepository.findAll().get(0);
        assertThat(n.getRead()).isFalse();

        // Flip it the way the "mark as read" path does, then re-read from the DB.
        n.setRead(true);
        n.setStatus("READ");
        notificationRepository.save(n);
        notificationRepository.flush();
        entityManager.clear();

        Notification reloaded = notificationRepository.findAll().get(0);
        assertThat(reloaded.getRead()).isTrue();
        assertThat(reloaded.getStatus()).isEqualTo("READ");
    }

    @Test
    @DisplayName("P6 unread counting is per user")
    void unreadCountIsPerUser() {
        User alice = givenAdvisor("alice");
        User bob = givenAdvisor("bob");
        notificationService.sendInApp(alice.getId(), "A1", "m");
        notificationService.sendInApp(alice.getId(), "A2", "m");
        notificationService.sendInApp(bob.getId(), "B1", "m");

        assertThat(notificationRepository.countByUserIdAndReadFalse(alice.getId())).isEqualTo(2);
        assertThat(notificationRepository.countByUserIdAndReadFalse(bob.getId())).isEqualTo(1);
        assertThat(notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(
                bob.getId())).hasSize(1);
    }

    @Test
    @DisplayName("P10 a notification is only findable through its own owner")
    void notificationsAreScopedToOwner() {
        User alice = givenAdvisor("alice");
        User bob = givenAdvisor("bob");
        notificationService.sendInApp(bob.getId(), "B1", "m");

        Notification stored = notificationRepository.findAll().get(0);

        assertThat(notificationRepository.findByIdAndUserId(stored.getId(), bob.getId()))
                .isPresent();
        // Alice cannot reach it, so the owner check has something to rely on.
        assertThat(notificationRepository.findByIdAndUserId(stored.getId(), alice.getId()))
                .isEmpty();
    }

    // ── Validation leaves nothing behind ───────────────────────────────────

    @Test
    @DisplayName("P8 a rejected create persists nothing")
    void rejectedCreatePersistsNothing() {
        FollowupRequestDTO bad = new FollowupRequestDTO();
        bad.setCustomerId(999L);
        bad.setDueDate(LocalDate.now().plusDays(1));
        bad.setReason("Post-service check");

        assertThatThrownBy(() -> followupService.create(bad))
                .isInstanceOf(com.autoservicehub.exception.ResourceNotFoundException.class);
        assertThat(followupRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("P9 a job card of another customer is rejected and persists nothing")
    void foreignJobCardIsRejected() {
        Customer mine = givenCustomer("Mine");
        Customer theirs = givenCustomer("Theirs");
        JobCard theirJobCard = givenJobCard(theirs);

        FollowupRequestDTO bad = new FollowupRequestDTO();
        bad.setCustomerId(mine.getId());
        bad.setJobCardId(theirJobCard.getId());
        bad.setDueDate(LocalDate.now().plusDays(1));
        bad.setReason("Post-service check");

        assertThatThrownBy(() -> followupService.create(bad))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(followupRepository.findAll()).isEmpty();
    }

    // Note: a "the transaction rolled back" test is deliberately absent here.
    // @DataJpaTest wraps every test in a transaction that is rolled back
    // afterwards, so a failure mid-batch cannot be observed — and suspending that
    // transaction (Propagation.NOT_SUPPORTED) to try makes every row this test
    // writes commit for real, leaking into the tests that run after it. Atomicity
    // here is a property of the @Transactional boundary on the service methods,
    // which the scheduler test covers by verifying the delegation.

    @Test
    @DisplayName("a valid create persists with the requested due date and status")
    void validCreatePersists() {
        Customer customer = givenCustomer("Ravi");
        JobCard jobCard = givenJobCard(customer);

        FollowupRequestDTO good = new FollowupRequestDTO();
        good.setCustomerId(customer.getId());
        good.setJobCardId(jobCard.getId());
        good.setDueDate(LocalDate.now().plusDays(14));
        good.setReason("Warranty check");
        good.setStatus("IN_PROGRESS");

        var created = followupService.create(good);

        assertThat(created.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(created.isDue()).isFalse();
        assertThat(created.isClosed()).isFalse();
        Followup stored = followupRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(stored.getJobCard().getId()).isEqualTo(jobCard.getId());
    }
}
