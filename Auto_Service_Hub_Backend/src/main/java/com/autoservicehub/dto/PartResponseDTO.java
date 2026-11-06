package com.autoservicehub.dto;

import com.autoservicehub.entity.PartCategory;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class PartResponseDTO {
    private Long id;
    private String sku;
    private String name;
    private String unit;
    private BigDecimal sellingPrice;
    private BigDecimal purchasePrice;
    private Integer stockQty;
    private Integer minStock;
    private boolean lowStock;

    /**
     * What kind of part this is; {@code null} when the part is uncategorised.
     *
     * <p>Serialised as the readable label (e.g. {@code "Engine Parts"}) so the
     * inventory list and filter can display the value directly.
     */
    private PartCategory category;

    /** Id of the usual supplier, or {@code null} when no supplier is set. */
    private Long supplierId;

    /**
     * Name of the usual supplier, or {@code null}.
     *
     * <p>Carried alongside {@link #supplierId} for the same reason
     * {@code PurchaseResponseDTO} does: the inventory list shows a name, so the
     * client would otherwise have to resolve every id.
     */
    private String supplierName;

    /** Where the part is kept, or {@code null}. */
    private String storageLocation;

    /** GST HSN code, or {@code null}. */
    private String hsnCode;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
