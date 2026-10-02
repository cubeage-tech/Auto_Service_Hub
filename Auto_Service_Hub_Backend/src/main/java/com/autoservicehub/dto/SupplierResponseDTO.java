package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Outbound payload for a supplier (FR-INV-2).
 * Never exposes the JPA entity directly (SRS 9.1).
 */
@Getter
@Setter
public class SupplierResponseDTO {
    private Long id;
    private String name;
    private String phone;
    private String email;
    private String address;

    /**
     * How many purchase orders this supplier has.
     *
     * <p>Surfaced on the supplier itself so a buyer can see at a glance which
     * suppliers are actually used before deleting or renaming one — the delete
     * rule turns on exactly this number.
     */
    private long purchaseCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}