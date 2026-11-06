package com.autoservicehub.dto;

import com.autoservicehub.entity.PartCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PartRequestDTO {
    @NotBlank
    private String sku;
    @NotBlank
    private String name;
    private String unit;
    @NotNull
    private BigDecimal sellingPrice;
    private BigDecimal purchasePrice;
    private Integer stockQty;
    private Integer minStock;

    /**
     * What kind of part this is. Optional — an unknown value is rejected with a
     * 400, and an absent one leaves the part uncategorised.
     */
    private PartCategory category;

    /**
     * The supplier this part is normally bought from, as an id from
     * {@code GET /api/v1/suppliers}.
     *
     * <p>Optional, but if supplied it must exist: an unknown id is rejected with
     * a 404 rather than being silently dropped, so a client that picked a stale
     * supplier learns about it instead of saving an unassigned part.
     */
    @Positive
    private Long supplierId;

    /** Where the part is kept, e.g. "Rack A1, Shelf 2". Optional. */
    private String storageLocation;

    /** GST HSN code. Optional. */
    private String hsnCode;
}
