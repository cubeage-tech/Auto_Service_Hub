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

    /**
     * Number of billable lines on an invoice.
     *
     * <p>Used by the FR-BILL-6 closure guard: an invoice with no lines has no
     * mandatory billing data, so it must not be treated as settled. A COUNT query
     * rather than loading the rows, since only the existence question is asked.
     */
    long countByInvoiceId(Long invoiceId);

    /**
     * Has this repair task's labour already been billed on this invoice?
     *
     * <p>The duplicate guard for labour-to-billing (FR-BILL-2). Billing the same
     * task's labour twice would charge the customer for one piece of work
     * twice, so the check is on the pair (invoice, task) rather than on the
     * description or the amount — a task is the unit that must not be billed
     * twice, whatever it happens to be called or cost.
     *
     * <p>Scoped to one invoice deliberately: the same task may legitimately
     * appear on a later invoice if the work was redone and rebilled, and
     * blocking that globally would be wrong.
     */
    boolean existsByInvoiceIdAndJobTaskId(Long invoiceId, Long jobTaskId);

    /**
     * The lines on one invoice that came from a repair task, in order.
     *
     * <p>Used by the invoice update path to carry task-sourced labour across a
     * line replacement, so editing an invoice cannot silently drop the labour
     * it was already billing.
     */
    List<InvoiceItem> findByInvoiceIdAndJobTaskIdIsNotNullOrderByIdAsc(Long invoiceId);
}
