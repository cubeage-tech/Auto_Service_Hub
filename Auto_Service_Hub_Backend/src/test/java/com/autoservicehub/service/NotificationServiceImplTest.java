package com.autoservicehub.service;

import com.autoservicehub.entity.Notification;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.NotificationRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-user in-app notification box (SRS 16, 18).
 *
 * The service resolves the caller from the SecurityContext — the JWT filter
 * authenticates with the username as the principal — and scopes every query by
 * that user's id.
 *
 * Test cases
 * ----------
 * N1  listing returns only the caller's notifications
 * N2  the unread listing is scoped to the caller
 * N3  the unread count is scoped to the caller
 * N4  marking one's own notification read flips read to true
 * N5  marking an already-read notification read is idempotent
 * N6  marking ANOTHER user's notification read  → 404 (IDOR)
 * N7  marking a non-existent notification read  → 404
 * N8  mark-all only touches the caller's unread ones
 * N9  a second mark-all changes nothing        → 0
 * N10 a call with no authenticated user is refused
 * N11 sendInApp persists a notification for the user
 * N12 notifyUserOnce creates once, then reports false
 * N13 the WhatsApp / email / SMS methods are still no-ops
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock NotificationRepository repository;
    @Mock UserRepository        userRepository;

    @InjectMocks NotificationServiceImpl service;

    private static final PageRequest PAGE = PageRequest.of(0, 10);

    // ── Fixtures ───────────────────────────────────────────────────────────

    private User user(Long id, String username) {
        Role role = new Role();
        role.setName("SERVICE_ADVISOR");
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setActive(true);
        return u;
    }

    private Notification notification(Long id, User owner, boolean read) {
        Notification n = new Notification();
        n.setId(id);
        n.setUser(owner);
        n.setChannel(NotificationServiceImpl.CHANNEL_IN_APP);
        n.setTitle("Follow-up due");
        n.setMessage("Follow-up 42 is due");
        n.setStatus(read ? "READ" : "UNREAD");
        n.setRead(read);
        n.setReferenceType(NotificationServiceImpl.REFERENCE_FOLLOWUP);
        n.setReferenceId(42L);
        return n;
    }

    /** Puts a username into the SecurityContext, as the JWT filter does. */
    private void authenticateAs(User u) {
        when(userRepository.findByUsernameIgnoreCase(u.getUsername())).thenReturn(Optional.of(u));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getUsername(), "n/a", List.of()));
    }

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void resetContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Listing and unread count ───────────────────────────────────────────

    @Test
    @DisplayName("N1 listing is scoped to the authenticated user")
    void listsOnlyMyNotifications() {
        User me = user(1L, "alice");
        authenticateAs(me);
        when(repository.findByUserIdOrderByCreatedAtDescIdDesc(1L, PAGE))
                .thenReturn(new PageImpl<>(List.of(notification(9L, me, false)), PAGE, 1));

        var page = service.listMine(PAGE);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Follow-up due");
        assertThat(page.getContent().get(0).isRead()).isFalse();
        assertThat(page.getContent().get(0).getReferenceId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("N2 the unread listing is scoped to the authenticated user")
    void listsOnlyMyUnreadNotifications() {
        User me = user(1L, "alice");
        authenticateAs(me);
        when(repository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(1L, PAGE))
                .thenReturn(new PageImpl<>(List.of(notification(9L, me, false)), PAGE, 1));

        assertThat(service.listMineUnread(PAGE).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("N3 the unread count is scoped to the authenticated user")
    void countsOnlyMyUnread() {
        User me = user(1L, "alice");
        authenticateAs(me);
        when(repository.countByUserIdAndReadFalse(1L)).thenReturn(3L);

        assertThat(service.countMineUnread()).isEqualTo(3L);
    }

    @Test
    @DisplayName("N10 a call with no authenticated user is refused")
    void refusesUnauthenticatedCall() {
        assertThatThrownBy(() -> service.countMineUnread())
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("an authenticated username with no user row is a 404")
    void unknownPrincipalIsRejected() {
        when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ghost", "n/a", List.of()));

        assertThatThrownBy(() -> service.countMineUnread())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Marking as read, including IDOR isolation ──────────────────────────

    @Test
    @DisplayName("N4 marking my own notification read flips it to read")
    void marksOwnNotificationRead() {
        User me = user(1L, "alice");
        authenticateAs(me);
        Notification n = notification(9L, me, false);
        when(repository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(n));
        when(repository.save(n)).thenReturn(n);

        var dto = service.markAsRead(9L);

        assertThat(dto.isRead()).isTrue();
        assertThat(n.getStatus()).isEqualTo("READ");
    }

    @Test
    @DisplayName("N5 marking an already-read notification read changes nothing further")
    void markingReadIsIdempotent() {
        User me = user(1L, "alice");
        authenticateAs(me);
        Notification n = notification(9L, me, true);
        when(repository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(n));

        assertThat(service.markAsRead(9L).isRead()).isTrue();
        // No redundant write.
        verify(repository, never()).save(n);
    }

    @Test
    @DisplayName("N6 I cannot mark another user's notification read (IDOR)")
    void cannotMarkAnotherUsersNotificationRead() {
        User me = user(1L, "alice");
        authenticateAs(me);

        // The lookup is scoped by owner, so Bob's notification is simply not found.
        when(repository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("5");
        // It never even looks in Bob's namespace.
        verify(repository, never()).findByIdAndUserId(5L, 2L);
    }

    @Test
    @DisplayName("N7 marking a non-existent notification read is a 404")
    void markMissingIsRejected() {
        User me = user(1L, "alice");
        authenticateAs(me);
        when(repository.findByIdAndUserId(404L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("N8 mark-all only touches the caller's unread notifications")
    void markAllOnlyTouchesMine() {
        User me = user(1L, "alice");
        authenticateAs(me);
        Notification a = notification(1L, me, false);
        Notification b = notification(2L, me, false);
        when(repository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(a, b));
        when(repository.saveAll(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(a, b));

        assertThat(service.markAllAsRead()).isEqualTo(2);
        assertThat(a.getRead()).isTrue();
        assertThat(b.getRead()).isTrue();
    }

    @Test
    @DisplayName("N9 a second mark-all changes nothing")
    void markAllIsIdempotent() {
        User me = user(1L, "alice");
        authenticateAs(me);
        when(repository.findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of());

        assertThat(service.markAllAsRead()).isZero();
    }

    // ── Provider-facing surface ────────────────────────────────────────────

    @Test
    @DisplayName("N11 sendInApp persists an unread notification for the user")
    void sendInAppPersistsNotification() {
        User me = user(1L, "alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(me));

        service.sendInApp(1L, "Job delivered", "Your car is ready");

        org.mockito.ArgumentCaptor<Notification> captor =
                org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(me);
        assertThat(captor.getValue().getChannel())
                .isEqualTo(NotificationServiceImpl.CHANNEL_IN_APP);
        assertThat(captor.getValue().getRead()).isFalse();
        assertThat(captor.getValue().getStatus()).isEqualTo("UNREAD");
    }

    @Test
    @DisplayName("sendInApp for an unknown user is a 404")
    void sendInAppRejectsUnknownUser() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendInApp(9L, "t", "m"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("N12 notifyUserOnce skips a recipient who already has it unread")
    void notifyUserOnceIsIdempotent() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "alice")));

        assertThat(service.notifyUserOnce(1L, "t", "m", "FOLLOWUP", 42L)).isTrue();

        // Second attempt: the recipient still has that reference unread.
        when(repository.existsByUserIdAndReferenceTypeAndReferenceIdAndReadFalse(1L, "FOLLOWUP", 42L))
                .thenReturn(true);
        assertThat(service.notifyUserOnce(1L, "t", "m", "FOLLOWUP", 42L)).isFalse();
    }

    @Test
    @DisplayName("notifyUserOnce notifies again once the earlier one has been read")
    void notifyUserOnceAfterRead() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "alice")));
        // The previous notification for this reference has been read.
        when(repository.existsByUserIdAndReferenceTypeAndReferenceIdAndReadFalse(1L, "FOLLOWUP", 42L))
                .thenReturn(false);

        assertThat(service.notifyUserOnce(1L, "t", "m", "FOLLOWUP", 42L)).isTrue();
    }

    @Test
    @DisplayName("notifyUserOnce for an unknown user is a 404")
    void notifyUserOnceRejectsUnknownUser() {
        when(repository.existsByUserIdAndReferenceTypeAndReferenceIdAndReadFalse(9L, "FOLLOWUP", 42L))
                .thenReturn(false);
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.notifyUserOnce(9L, "t", "m", "FOLLOWUP", 42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("N13 the WhatsApp, email and SMS methods are still no-ops")
    void providerMethodsAreNoOps() {
        // No provider is integrated yet, so these must neither persist nor throw.
        service.sendEmail("a@b.com", "subject", "body");
        service.sendWhatsApp("+9198", "template", java.util.Map.of());
        service.sendSms("+9198", "message");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
