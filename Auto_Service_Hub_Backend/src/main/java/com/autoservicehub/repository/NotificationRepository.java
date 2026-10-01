package com.autoservicehub.repository;

import com.autoservicehub.entity.Notification;
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
	List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);
	Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
}
