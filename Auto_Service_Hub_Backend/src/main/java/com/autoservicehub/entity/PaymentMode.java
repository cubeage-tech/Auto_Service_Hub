package com.autoservicehub.entity;

import com.autoservicehub.exception.BusinessRuleException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The ways money can actually be handed over for a payment (Billing - Payments,
 * SRS 4.9).
 *
 * <p>A fixed vocabulary rather than free text, because {@code mode} is a
 * reporting discriminator: {@code ReportExportServiceImpl} groups payments by
 * it ("Payments by mode") and {@code PaymentModeTotalProjection} reads it
 * straight off the column. A free-text column cannot promise that every row
 * answers "is this a card payment?" the same way — "Card", "card" and "CREDIT
 * CARD" would all be separate buckets in the revenue report, and nothing would
 * complain. Enumerating the values means a bad one is refused at the point it is
 * recorded instead of quietly corrupting the report later.
 *
 * <p><strong>PAY_LATER IS DELIBERATELY NOT A MEMBER.</strong> "Pay Later" is a
 * choice about an <em>invoice</em>, not a description of a <em>payment</em>: it
 * means no money has changed hands yet. There is no amount received to record,
 * and the schema has nowhere to put a promise to pay later — no due date, no
 * credit term, no promised settlement date. Treating it as a payment mode would
 * be actively wrong: a payment row reduces the invoice's outstanding balance and
 * can drive the invoice to PAID, so a "Pay Later" payment would mark an invoice
 * as fully paid when nothing was ever received.
 *
 * <p>The correct handling of Pay Later is therefore what the UI does: save the
 * invoice and leave it PENDING with its full balance outstanding, recording no
 * payment at all. A client that tries to post one gets the message below rather
 * than a silent success that would contradict the invoice's own balance.
 *
 * <p>Note this enum is a <em>vocabulary for validating input</em>, not the
 * column's type: {@link Payment#mode} stays a nullable {@link String}. Payments
 * written before this enum existed were never validated, so the column may
 * already hold other values; keeping it a String means those rows still read
 * back rather than failing to load.
 */
public enum PaymentMode {

    /** Mobile-app payment (UPI / IMPS / QR). */
    UPI,

    /** Physical notes handed over at the counter. */
    CASH,

    /** Debit or credit card, entered by staff. No gateway integration exists. */
    CARD,

    /** Funds moved to the workshop's bank account. */
    BANK_TRANSFER;

    /** Accepted spellings of each mode, normalised to the enum constant. */
    private static final PaymentMode[] VALUES = values();

    /** The constant name as stored on {@link Payment#mode}. */
    public String storedValue() {
        return name();
    }

    /**
     * Resolves and validates a client-supplied payment mode.
     *
     * <p>Accepts the stored constant, case-insensitively, so both {@code UPI}
     * and {@code upi} are the same mode. {@code null} or blank is allowed and
     * returns {@code null}: mode has always been optional and is nullable on the
     * entity, so requiring it now would break older clients that never sent it.
     *
     * @param requested the mode from the request, may be null
     * @return the normalised mode, or null when none was supplied
     * @throws BusinessRuleException if a value is supplied that is not a real
     *         payment mode — including PAY_LATER, which is not a payment
     */
    public static PaymentMode resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }

        String candidate = requested.trim();

        // PAY_LATER gets its own message. It is a legitimate thing for a user to
        // want, so saying "unsupported mode" would be confusing — the real point
        // is that it is not a payment, and the correct action is to leave the
        // invoice unpaid.
        if (PAY_LATER_ALIASES.contains(candidate.toUpperCase())) {
            throw new BusinessRuleException(
                    "PAY_LATER is not a payment mode: it records no money, so it cannot be saved "
                            + "as a payment. Leave the invoice unpaid (PENDING) with its full balance "
                            + "outstanding instead.");
        }

        return Arrays.stream(VALUES)
                .filter(mode -> mode.name().equalsIgnoreCase(candidate))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "Unsupported payment mode: \"" + requested + "\". Supported modes are "
                                + supportedValues() + "."));
    }

    /** Spellings that mean "pay later", all of which are refused as payments. */
    private static final Set<String> PAY_LATER_ALIASES =
            Set.of("PAY_LATER", "PAY LATER", "PAYLATER", "DEFERRED", "CREDIT");

    /** Comma-separated stored values, for error messages and docs. */
    public static String supportedValues() {
        return Arrays.stream(VALUES)
                .map(PaymentMode::name)
                .collect(Collectors.joining(", "));
    }

    /** The supported modes as a set, for callers that want to offer a list. */
    public static Set<String> supported() {
        return Arrays.stream(VALUES)
                .map(PaymentMode::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}