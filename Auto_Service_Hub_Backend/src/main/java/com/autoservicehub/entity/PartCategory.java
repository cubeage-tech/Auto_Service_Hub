package com.autoservicehub.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of spare part a {@link Part} is (FR-INV-1).
 *
 * <p>An enum rather than a free string, for the same reason
 * {@link BillingItemCategory} is one: the inventory view groups and filters by
 * category, so the value has to be one of a known set for the filter to be
 * trustworthy. A typo in a free string would silently create a category of one
 * that nothing else ever matches.
 *
 * <p>Persisted with {@code EnumType.STRING} so the stored values stay readable
 * and stable if the constants are ever reordered.
 *
 * <p>The column is nullable. Parts created before this column existed have no
 * category, and they continue to work uncategorised rather than requiring a
 * backfill migration; {@code null} reads as "uncategorised" in the UI.
 *
 * <p>Accepts the wire names {@code ENGINE_PARTS} and {@code OTHER} as well as
 * the stored constant names, so a client may send either
 * {@code "ENGINE_PARTS"} or {@code "ENGINE PARTS"}. Anything else is rejected
 * with a 400 rather than being stored as an unknown category.
 */
public enum PartCategory {

    /** Oils, greases and other fluids consumed during servicing. */
    LUBRICANTS,

    /** Brake pads, discs, calipers and related friction hardware. */
    BRAKES,

    /** Oil, air, cabin and fuel filters. */
    FILTERS,

    /** Spark plugs, ignition coils, glow plugs and related hardware. */
    IGNITION,

    /** Tyres, tubes and wheel accessories. */
    TYRES,

    /** Batteries, alternators, starters, bulbs and wiring. */
    ELECTRICAL,

    /** Gaskets, pistons, valves, belts and other engine hardware. */
    ENGINE_PARTS,

    /** Shock absorbers, struts, bushings and suspension hardware. */
    SUSPENSION,

    /**
     * Anything that fits none of the categories above.
     *
     * <p>Distinct from {@code null}: a part categorised as OTHER has been
     * deliberately filed, whereas a null category means it was never filed.
     */
    OTHER;

    /** Human-facing label used by the inventory list and filter. */
    @JsonValue
    public String getLabel() {
        return Arrays.stream(name().split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    /**
     * Accepts a constant name ({@code ENGINE_PARTS}) or a spaced label
     * ({@code Engine Parts}), case-insensitively and ignoring surrounding
     * whitespace, so the value a UI displays can be sent straight back.
     *
     * @throws IllegalArgumentException when the value matches no category.
     *         Jackson wraps this in a 400 with a generic "malformed body"
     *         message, which is the correct status — an unknown category is a
     *         bad request, not a server fault.
     */
    @JsonCreator
    public static PartCategory fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (PartCategory category : values()) {
            if (category.name().equalsIgnoreCase(normalized)
                    || category.getLabel().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown part category: " + value);
    }
}