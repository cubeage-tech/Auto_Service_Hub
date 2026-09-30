package com.autoservicehub.repository;

import com.autoservicehub.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for Notification. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    /** One user's notifications, newest first. */
    List<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** Paged variant of the above. */
    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** One user's unread notifications, newest first. */
    List<Notification> findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(Long userId);

    /** Paged variant of the above. */
    Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    /** How many notifications a user has not read yet. */
    long countByUserIdAndReadFalse(Long userId);

    /** A notification the given user owns; the basis of the per-user access check. */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /**
     * Whether the user still has an <em>unread</em> notification for this
     * reference.
     *
     * <p>Read is what makes the difference. It is the guard that stops the
     * scheduler producing two identical unread reminders for the same follow-up.
     * Once the recipient has read it, the follow-up's closed → open transition
     * is allowed to raise a fresh one, which is the whole point of re-opening it.
     */
    boolean existsByUserIdAndReferenceTypeAndReferenceIdAndReadFalse(
            Long userId, String referenceType, Long referenceId);
}
