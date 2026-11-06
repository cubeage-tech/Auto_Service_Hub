package com.autoservicehub.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Applying a service package to a customer's vehicle.
 *
 * <p>Nothing about the package itself is accepted here. The price, GST rate,
 * discount and duration are read off {@code ServicePackage} by the service and
 * snapshotted onto the resulting row, so a client cannot quote terms that the
 * catalogue never agreed to — the whole point of the snapshot is that it records
 * what was actually on offer, not what the caller says it was.
 */
@Getter
@Setter
public class PackageApplicationRequestDTO {

    /** The package to apply. Required; a missing package answers 404. */
    @NotNull(message = "servicePackageId is required")
    private Long servicePackageId;

    /** The customer the package is sold to. Required. */
    @NotNull(message = "customerId is required")
    private Long customerId;

    /**
     * The vehicle the package is applied to. Required.
     *
     * <p>Must belong to {@code customerId}; a vehicle owned by somebody else is
     * rejected with 409 rather than quietly recorded against the wrong customer.
     */
    @NotNull(message = "vehicleId is required")
    private Long vehicleId;
}
