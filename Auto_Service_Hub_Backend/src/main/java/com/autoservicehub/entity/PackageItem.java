package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'package_items' table (SRS section 8.2 High-Level Entities).
 *
 * <p>The entity existed but was unreachable: it had no link back to
 * {@link ServicePackage}, so no item row could ever be attached to a package
 * and nothing in the application referenced it. {@link #servicePackage} supplies
 * that missing link and makes the table usable.
 */
@Getter
@Setter
@Entity
@Table(name = "package_items", indexes = {
        @Index(name = "idx_package_item_service_package_id", columnList = "service_package_id")
})
public class PackageItem extends BaseEntity {

    @Column(name = "item_name")
    private String itemName;

    /** SERVICE or PART — see {@link PackageItemType}. */
    @Column(name = "item_type")
    private String itemType;

    /**
     * The package this item belongs to.
     *
     * <p>Optional so the column can be added to any existing table without a
     * backfill, and so an item can be staged before its package is known.
     * {@code FetchType.LAZY} because every package screen loads its items and
     * nothing else needs them.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_package_id")
    private ServicePackage servicePackage;
}