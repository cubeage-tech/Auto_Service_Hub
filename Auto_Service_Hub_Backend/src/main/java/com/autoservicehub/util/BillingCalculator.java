package com.autoservicehub.util;

import com.autoservicehub.exception.BusinessRuleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Server-side money calculation for estimates and invoices (Billing, SRS 4.9).
 *
 * <p>The single place any amount is computed. Client-supplied subtotal, tax/GST
 * and total are never trusted or stored — only the line items and the discount
 * come from the request, and everything else is derived here.
 *
 * <p>Rules:
 * <ul>
 *   <li>line amount = quantity × unit price</li>
 *   <li>subtotal    = sum of line amounts</li>
 *   <li>taxable     = subtotal − discount</li>
 *   <li>tax / GST   = taxable × rate ÷ 100</li>
 *   <li>total       = taxable + tax</li>
 * </ul>
 *
 * <p>Tax is charged on the post-discount amount, which is the standard
 * treatment: a discount reduces the value of the supply, so it reduces the tax
 * base. The rate defaults to 18% — the rate the project's own UI already
 * displays ("GST (18%)", "+ 18% GST") — and is overridable via
 * {@code app.billing.tax-rate-percent} without touching application.yml.
 *
 * <p>All arithmetic uses {@link BigDecimal} at {@link #MONEY_SCALE} decimal
 * places with {@link RoundingMode#HALF_UP}, and never uses {@code double}.
 */
@Component
public class BillingCalculator {

    /** Money precision: two decimal places, as is standard for currency. */
    public static final int MONEY_SCALE = 2;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final BigDecimal taxRatePercent;

    public BillingCalculator(
            @Value("${app.billing.tax-rate-percent:18}") BigDecimal taxRatePercent) {
        if (taxRatePercent == null || taxRatePercent.signum() < 0) {
            throw new IllegalArgumentException(
                    "app.billing.tax-rate-percent must be a non-negative number");
        }
        this.taxRatePercent = taxRatePercent;
    }

    /** The configured tax/GST rate, as a percentage. */
    public BigDecimal getTaxRatePercent() {
        return taxRatePercent;
    }

    /**
     * Validates and normalises a client-supplied line item.
     *
     * @throws BusinessRuleException if quantity or unit price is missing, zero,
     *         negative, or the price carries sub-paise precision
     */
    public BigDecimal lineAmount(Integer quantity, BigDecimal unitPrice) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessRuleException(
                    "quantity must be greater than 0, got: " + quantity);
        }
        if (unitPrice == null) {
            throw new BusinessRuleException("unitPrice is required for every line item.");
        }
        if (unitPrice.signum() < 0) {
            throw new BusinessRuleException(
                    "unitPrice must not be negative, got: " + unitPrice.toPlainString());
        }
        if (unitPrice.scale() > MONEY_SCALE
                && unitPrice.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new BusinessRuleException(
                    "unitPrice must not have more than " + MONEY_SCALE + " decimal places, got: "
                            + unitPrice.toPlainString());
        }
        return money(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /**
     * Validates a flat discount against the subtotal.
     *
     * @return the discount, normalised to money scale
     * @throws BusinessRuleException if negative, or larger than the subtotal
     */
    public BigDecimal discount(BigDecimal requestedDiscount, BigDecimal subtotal) {
        if (requestedDiscount == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        if (requestedDiscount.signum() < 0) {
            throw new BusinessRuleException(
                    "discount must not be negative, got: " + requestedDiscount.toPlainString());
        }
        if (requestedDiscount.compareTo(subtotal) > 0) {
            throw new BusinessRuleException(
                    "discount (" + requestedDiscount.toPlainString()
                            + ") cannot exceed the subtotal (" + subtotal.toPlainString() + ").");
        }
        return money(requestedDiscount);
    }

    /** Sum of the line amounts. */
    public BigDecimal subtotal(BigDecimal sumOfLineAmounts) {
        return money(sumOfLineAmounts == null ? BigDecimal.ZERO : sumOfLineAmounts);
    }

    /** taxable amount = subtotal − discount. */
    public BigDecimal taxableAmount(BigDecimal subtotal, BigDecimal discount) {
        return money(subtotal.subtract(discount == null ? BigDecimal.ZERO : discount));
    }

    /** tax = taxable × rate ÷ 100, rounded to money scale. */
    public BigDecimal tax(BigDecimal taxableAmount) {
        if (taxableAmount == null || taxableAmount.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return money(taxableAmount.multiply(taxRatePercent).divide(ONE_HUNDRED, 10, RoundingMode.HALF_UP));
    }

    /** total = taxable + tax. */
    public BigDecimal total(BigDecimal taxableAmount, BigDecimal tax) {
        return money(taxableAmount.add(tax == null ? BigDecimal.ZERO : tax));
    }

    /** Rounds to money scale with HALF_UP, avoiding {@code ArithmeticException}. */
    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
