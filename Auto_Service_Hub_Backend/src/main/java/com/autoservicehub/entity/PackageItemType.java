package com.autoservicehub.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whether a {@link PackageItem} is work performed or a physical item consumed
 * (FR-PKG-1).
 *
 * <p>An enum rather than a free string, because the package card renders the
 * two kinds differently — services as a ticked list, parts as tags — so the
 * distinction has to survive the round trip reliably.
 *
 * <p>Persisted with {@code EnumType.STRING} so stored values stay readable.
 */
public enum PackageItemType {

    /** Labour or a task bundled into the package, e.g. "Oil filter replacement". */
    SERVICE,

    /** A spare part or consumable supplied with the package, e.g. "Engine Oil 5L". */
    PART;

    @JsonValue
    public String getValue() {
        return name();
    }

    /**
     * Accepts the constant name case-insensitively and ignoring surrounding
     * whitespace, so a client may send {@code "PART"} or {@code " part "}.
     *
     * @throws IllegalArgumentException when the value matches neither type.
     *         Jackson wraps this in a 400, which is the correct status — an
     *         unknown item type is a bad request, not a server fault.
     */
    @JsonCreator
    public static PackageItemType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (PackageItemType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown package item type: " + value);
    }

    /**
     * Lenient lookup for values already in the database.
     *
     * <p>Unlike {@link #fromValue(String)} this returns null instead of throwing
     * for an unrecognised value. Reading a persisted row must never fail a GET:
     * an item written by an older build or edited directly in the database
     * should render with an unknown type, not turn the whole package list into a
     * 500.
     */
    public static PackageItemType fromStored(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        for (PackageItemType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }
}