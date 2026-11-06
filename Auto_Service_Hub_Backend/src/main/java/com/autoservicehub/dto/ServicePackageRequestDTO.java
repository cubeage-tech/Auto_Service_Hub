package com.autoservicehub.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ServicePackageRequestDTO {
    @NotBlank
    private String name;

    /**
     * The package code shown on the card ("BASIC", "PREM-PLUS", "AMC").
     * Free text, as it has always been.
     */
    @NotBlank
    private String type;

    @NotNull
    private BigDecimal price;

    /**
     * A soft-delete flag: an inactive package stays queryable but should not be
     * offered to customers.
     *
     * <p>Optional on create, where it defaults to true. On update it is honoured
     * only when sent, so omitting it preserves the stored value rather than
     * silently reactivating a deactivated package.
     */
    private Boolean active;

    /** How long the package takes or stays valid, e.g. "4–5 hrs". Optional. */
    private String duration;

    /** GST percentage applied on top of the price, e.g. 18. Optional. */
    @DecimalMin(value = "0.0", message = "gstRate must not be negative")
    @DecimalMax(value = "100.0", message = "gstRate must not exceed 100")
    private BigDecimal gstRate;

    /**
     * Discount advertised on the card, as a percentage. Display only — it does
     * not change {@link #price}.
     */
    @DecimalMin(value = "0.0", message = "discountPercentage must not be negative")
    @DecimalMax(value = "100.0", message = "discountPercentage must not exceed 100")
    private BigDecimal discountPercentage;

    /**
     * Accent colour token for the package card. Must be one of the tokens the
     * card stylesheet defines; an unknown value is rejected with a 409 listing
     * the accepted set, rather than silently producing an unstyled card.
     */
    private String color;

    /** Whether the card shows a "Popular" badge. Optional; defaults to false. */
    private Boolean popular;

    /**
     * The services and parts bundled into this package.
     *
     * <p>Optional, and treated as a full replacement: sending a list replaces
     * the stored items outright, and omitting the field leaves them alone.
     */
    @Valid
    private List<PackageItemRequestDTO> items;
}