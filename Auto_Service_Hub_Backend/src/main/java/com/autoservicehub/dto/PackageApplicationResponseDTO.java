package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One applied package, as stored.
 *
 * <p>The {@code package*} fields are the snapshot taken at application time, not
 * the package's current values — see {@link com.autoservicehub.entity.PackageApplication}.
 * A response can therefore show two prices: {@code packagePrice} is what this
 * customer agreed, while the linked {@code servicePackageId} is where the
 * catalogue entry lives today.
 *
 * <p>Customer and vehicle are reported live through their relationships, so a
 * corrected name or registration shows up here without rewriting history.
 */
@Getter
@Setter
public class PackageApplicationResponseDTO {

    private Long id;

    /** Catalogue link — survives a package being renamed or repriced. */
    private Long servicePackageId;

    /* ---- Snapshot of the terms agreed at application time ---- */

    private String packageName;
    private String packageType;
    private BigDecimal packagePrice;
    private BigDecimal packageGstRate;
    private BigDecimal packageDiscountPercentage;
    private String packageDuration;

    /* ---- Applied to ---- */

    private Long customerId;
    private String customerName;

    private Long vehicleId;
    private String vehicleRegistrationNo;

    private LocalDateTime appliedAt;
    private LocalDateTime updatedAt;
}
