package com.autoservicehub.entity;

/**
 * What kind of thing a billing line is (FR-BILL-2: parts, labour, packages,
 * discounts and taxes).
 *
 * <p>An enum rather than a free string, because the whole point of the column
 * is to be a reliable discriminator: reports and the labour-to-invoice
 * integration have to be able to ask "is this line labour?" and get the same
 * answer every time, which a string column cannot promise. It is persisted with
 * {@code EnumType.STRING} so the stored values stay readable and stable even if
 * the constants are ever reordered.
 *
 * <p>The column is nullable, and {@code null} is read as {@link #PART}. That is
 * what lets every invoice and estimate line written before this column existed
 * continue to mean "a part" without a data migration to backfill it — an
 * existing line cannot have been labour, because labour was never integrated.
 */
public enum BillingItemCategory {

    /** A spare part drawn from inventory. The default for a line with no category set. */
    PART,

    /** Technician time for a repair task — see {@code JobTask.labourCost}. */
    LABOUR,

    /** A bundled service sold at a single price (see {@code ServicePackage}). */
    PACKAGE,

    /** Anything else billable: a consumable, a shop fee, a third-party charge. */
    OTHER
}