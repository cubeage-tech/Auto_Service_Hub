package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;


/**
 * Maps to the 'package_applications' table (SRS section 8.2 High-Level Entities).
 *
 * <p>One record of a {@link ServicePackage} being sold to a customer for one of
 * their {@link Vehicle}s. This is the missing link that made "Apply Package" a
 * no-op: before this entity existed a package could be defined but nothing
 * recorded that anyone had actually taken one.
 *
 * <p><strong>Why the package terms are copied onto this row.</strong> The
 * {@code package_*} columns are a snapshot taken at the moment of application,
 * not a view of the parent package. A package's price, GST rate, discount and
 * duration are editable at any time, so a row that read them back through the
 * relationship would silently restate history: raising a price tomorrow would
 * retroactively change what this customer was quoted. Copying them means the
 * record of what was agreed stays true no matter what the catalogue does next.
 * The {@link ServicePackage} relationship is kept alongside so the application
 * can still be traced back to (and reported against) its package, and so the
 * duplicate rule can be expressed as a real database constraint.
 *
 * <p><strong>What is not snapshotted.</strong> The package's item list is not
 * copied. Items are an editable checklist rather than a priced term, and
 * duplicating them per application would mean a second child table with its
 * own lifecycle for very little gain. The snapshot deliberately covers what was
 * agreed on — name, code, price, GST, discount, duration — which is what the
 * billing and reporting side needs.
 *
 * <p><strong>Customer and vehicle are read live, not copied.</strong> Unlike the
 * package terms, these are ownership links rather than agreed terms, and a
 * vehicle's registration or a customer's name changing is a correction to the
 * same entity, not a new fact. They are {@code optional = false}: an
 * application without an owner or a vehicle could never be attributed or
 * reported on.
 */
@Getter
@Setter
@Entity
@Table(name = "package_applications",
        indexes = {
                @Index(name = "idx_package_application_service_package_id", columnList = "service_package_id"),
                @Index(name = "idx_package_application_customer_id", columnList = "customer_id"),
                @Index(name = "idx_package_application_vehicle_id", columnList = "vehicle_id")
        },
        /*
         * One package may be applied to a given vehicle only once. This is
         * enforced here as well as in the service layer because a service-layer
         * check alone races: two advisors pressing Apply at the same moment would
         * both pass it. The constraint is what actually makes it true, and a
         * loser gets a 409 from GlobalExceptionHandler rather than a duplicate row.
         *
         * The restriction is per (package, vehicle) rather than per customer, so
         * a customer with two cars can hold the same package on both. Renewal is
         * expressed by the catalogue changing, not by re-applying the identical
         * package to the identical vehicle at an identical price.
         */
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_package_application_package_vehicle",
                        columnNames = {"service_package_id", "vehicle_id"})
        })
public class PackageApplication extends BaseEntity {

    /** The package that was applied. Never null. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_package_id", nullable = false)
    private ServicePackage servicePackage;

    /** The customer the package was sold to. Never null. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** The vehicle the package was applied to. Never null. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    /* ---- Snapshot of the package terms, taken at application time ---- */

    /** Package name as it read when applied. */
    @Column(name = "package_name")
    private String packageName;

    /** Package code as it read when applied ("BASIC", "AMC"). */
    @Column(name = "package_type")
    private String packageType;

    /** Headline price as it read when applied, before any GST. */
    @Column(name = "package_price")
    private BigDecimal packagePrice;

    /** GST percentage advertised at the time. Null when none was set. */
    @Column(name = "package_gst_rate")
    private BigDecimal packageGstRate;

    /** Advertised discount percentage at the time. Null when none was set. */
    @Column(name = "package_discount_percentage")
    private BigDecimal packageDiscountPercentage;

    /** Duration/validity text at the time. Null when none was set. */
    @Column(name = "package_duration")
    private String packageDuration;
}
