package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * Maps to the 'parts' table (SRS section 8.2 High-Level Entities).
 *
 * <p>{@code category}, {@code storageLocation}, {@code hsnCode} and the
 * {@code supplier} reference were added after the first release. All four are
 * nullable and are added by {@code ddl-auto: update} as new columns, so existing
 * part rows keep their data and read back as uncategorised / unassigned.
 */
@Getter
@Setter
@Entity
@Table(name = "parts")
public class Part extends BaseEntity {

    @Column(name = "sku")
    private String sku;
    @Column(name = "name")
    private String name;

    /**
     * Free text rather than an enum, matching {@code PartRequestDTO.unit} — a
     * workshop counts in pieces, sets, litres and kilograms interchangeably and
     * constraining it would reject legitimate units.
     */
    @Column(name = "unit")
    private String unit;

    @Column(name = "selling_price")
    private BigDecimal sellingPrice;
    @Column(name = "purchase_price")
    private BigDecimal purchasePrice;
    @Column(name = "stock_qty")
    private Integer stockQty;
    @Column(name = "min_stock")
    private Integer minStock;

    /**
     * What kind of part this is, used to group and filter the inventory list.
     * Nullable: parts filed before this column existed remain uncategorised.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    private PartCategory category;

    /**
     * Where the part is physically kept ("Rack A1, Shelf 2"). Purely a label for
     * staff — it drives no query and no business rule.
     */
    @Column(name = "storage_location")
    private String storageLocation;

    /**
     * The GST HSN code for this part, used when the part appears on an invoice.
     * Free text rather than numeric because HSN codes are 4, 6 or 8 digits
     * depending on the tariff schedule, and some are alphanumeric.
     */
    @Column(name = "hsn_code")
    private String hsnCode;

    /**
     * The supplier this part is normally bought from.
     *
     * <p>Optional and {@code FetchType.LAZY}: most parts have no preferred
     * supplier, and the part list must not trigger a query per row. A part can
     * legitimately be bought from several suppliers — {@code Purchase} records
     * the actual supplier per order — so this is the usual source, not an
     * exclusive one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;
}
