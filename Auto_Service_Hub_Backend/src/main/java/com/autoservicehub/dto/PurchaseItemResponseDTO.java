package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Outbound payload for one line of a purchase (FR-INV-2).
 *
 * <p>{@code lineAmount} is the server-calculated quantity * unitPrice, so a
 * client can reconcile what it ordered against what was stored.
 */
@Getter
@Setter
public class PurchaseItemResponseDTO {
    private Long id;
    private Long partId;
    private String partSku;
    private String partName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineAmount;
}