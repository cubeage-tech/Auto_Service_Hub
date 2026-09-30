package com.autoservicehub.repository;

import com.autoservicehub.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for InvoiceItem. Extends JpaSpecificationExecutor so
 * list/report endpoints (SRS 9, 17) can apply dynamic filters.
 */
@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long>, JpaSpecificationExecutor<InvoiceItem> {

    /**
     * Billed lines of one invoice, in recorded order. Ordered by id rather than
     * createdAt because a batch of items saved in the same transaction shares
     * the same createdAt timestamp.
     */
    List<InvoiceItem> findByInvoiceIdOrderByIdAsc(Long invoiceId);
}
