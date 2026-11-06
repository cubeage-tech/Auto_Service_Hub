package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


/**
 * Maps to the 'service_packages' table (SRS section 8.2 High-Level Entities).
 *
 * <p>{@code duration}, {@code gstRate}, {@code discountPercentage},
 * {@code color}, {@code popular} and the {@code items} collection were added
 * after the first release. All of them are optional, so packages created before
 * this change keep working and simply have no duration, no discount and no items.
 */
@Getter
@Setter
@Entity
@Table(name = "service_packages")
public class ServicePackage extends BaseEntity {

    @Column(name = "name")
    private String name;

    /**
     * The package code shown on the card ("BASIC", "PREM-PLUS", "AMC").
     * Free text, as it always has been — workshops define their own scheme.
     */
    @Column(name = "type")
    private String type;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "active")
    private Boolean active;

    /**
     * How long the package takes, or how long it stays valid — "4–5 hrs",
     * "12 months".
     *
     * <p>Free text rather than a duration value plus a unit, because a package
     * can be described either way and a workshop may legitimately want to say
     * "4–5 hrs" without committing to a single number.
     */
    @Column(name = "duration")
    private String duration;

    /** GST rate applied on top of {@link #price}, as a percentage (e.g. 18.00). */
    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    /**
     * Discount advertised on the package card, as a percentage (e.g. 10.00).
     *
     * <p>Display only. It does not adjust {@link #price} and is not applied at
     * billing — an actual discount belongs on the invoice line, which is where
     * the money actually changes.
     */
    @Column(name = "discount_percentage")
    private BigDecimal discountPercentage;

    /**
     * Accent colour for the package card, drawn from a fixed set of tokens
     * ({@code lime}, {@code blue}, {@code purple}, {@code orange},
     * {@code green}, {@code red}).
     *
     * <p>Validated in {@code ServicePackageServiceImpl} against that set rather
     * than being an enum: the stored value has to stay a lower-case CSS token
     * the card stylesheet can match directly.
     */
    @Column(name = "color", length = 20)
    private String color;

    /** Whether the card shows a "Popular" badge. */
    @Column(name = "popular")
    private Boolean popular;

    /**
     * The services and parts bundled into this package.
     *
     * <p>{@code cascade} so deleting a package takes its items with it rather
     * than leaving orphans; {@code orphanRemoval} so replacing the item list on
     * update deletes the rows that dropped out of it.
     */
    @OneToMany(mappedBy = "servicePackage", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackageItem> items = new ArrayList<>();
}