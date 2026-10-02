package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound payload for a purchase order (FR-INV-2).
 *
 * <p>{@code status} is PENDING until the order is received, at which point it
 * becomes RECEIVED and the goods have been added to stock as IN movements.
 */
@Getter
@Setter
public class PurchaseResponseDTO {
    private Long id;
    private Long supplierId;
    private String supplierName;
    private LocalDate purchaseDate;

    /** Server-calculated sum of the line amounts. */
    private BigDecimal totalAmount;

    /** PENDING | RECEIVED. */
    private String status;

    private List<PurchaseItemResponseDTO> items;

    private LocalDateTime createdAt;
}