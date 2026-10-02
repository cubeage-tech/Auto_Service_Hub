package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for billing a repair task's labour onto an invoice
 * (FR-BILL-2).
 *
 * <p>Only {@code jobTaskId} is accepted. The description, quantity and unit
 * price are all derived from the task itself:
 *
 * <ul>
 *   <li>description — the task's description, so the customer sees what was
 *       actually done rather than whatever the caller typed.</li>
 *   <li>quantity — always 1. A task is one piece of work; letting the caller
 *       multiply it would let one task's labour be billed many times over.</li>
 *   <li>unit price — {@code JobTask.labourCost}, the amount the workshop
 *       recorded as incurred.</li>
 * </ul>
 *
 * <p>The line amount, GST, discount and invoice total are all recalculated
 * server-side by {@code BillingCalculator}; nothing monetary is accepted here.
 */
@Getter
@Setter
public class InvoiceLabourItemRequestDTO {

    @NotNull(message = "jobTaskId is required")
    private Long jobTaskId;
}