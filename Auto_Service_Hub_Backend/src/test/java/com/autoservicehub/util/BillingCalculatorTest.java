package com.autoservicehub.util;

import com.autoservicehub.exception.BusinessRuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BillingCalculator} — the server-side money rules.
 * No Spring context: the calculator is constructed directly.
 *
 * Test cases
 * ----------
 * B1–B7   line amount: quantity × price, rounding, and input validation
 * B8–B11  discount: default, negative, above subtotal, equal to subtotal
 * B12–B17 subtotal / taxable / tax / total and the full chain
 * B18–B19 configurable rate and money() rounding
 */
class BillingCalculatorTest {

    private BillingCalculator calculator() {
        return new BillingCalculator(new BigDecimal("18"));
    }

    // ── Line amounts ──────────────────────────────────────────────────────

    @Test
    @DisplayName("B1 — line amount is quantity × unit price")
    void b1_lineAmount_isQuantityTimesPrice() {
        assertThat(calculator().lineAmount(3, new BigDecimal("250.50")))
                .isEqualByComparingTo("751.50");
    }

    @Test
    @DisplayName("B2 — a line amount is exact at money scale for a 2dp price")
    void b2_lineAmount_isExact() {
        assertThat(calculator().lineAmount(3, new BigDecimal("33.33")))
                .isEqualByComparingTo("99.99");
    }

    @Test
    @DisplayName("B3 — zero quantity → BusinessRuleException")
    void b3_zeroQuantity_throws() {
        assertThatThrownBy(() -> calculator().lineAmount(0, BigDecimal.TEN))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity must be greater than 0");
    }

    @Test
    @DisplayName("B4 — negative quantity → BusinessRuleException")
    void b4_negativeQuantity_throws() {
        assertThatThrownBy(() -> calculator().lineAmount(-2, BigDecimal.TEN))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("B5 — null unit price → BusinessRuleException")
    void b5_nullUnitPrice_throws() {
        assertThatThrownBy(() -> calculator().lineAmount(1, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unitPrice is required");
    }

    @Test
    @DisplayName("B6 — negative unit price → BusinessRuleException")
    void b6_negativeUnitPrice_throws() {
        assertThatThrownBy(() -> calculator().lineAmount(1, new BigDecimal("-5")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unitPrice must not be negative");
    }

    @Test
    @DisplayName("B7 — unit price with sub-paise precision → BusinessRuleException")
    void b7_subPaisePrecision_throws() {
        assertThatThrownBy(() -> calculator().lineAmount(1, new BigDecimal("10.005")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    @DisplayName("B7b — a price whose extra decimals are trailing zeros is accepted")
    void b7b_trailingZerosAccepted() {
        assertThat(calculator().lineAmount(1, new BigDecimal("10.5000")))
                .isEqualByComparingTo("10.50");
    }

    // ── Discount ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("B8 — a null discount defaults to zero")
    void b8_nullDiscount_isZero() {
        assertThat(calculator().discount(null, new BigDecimal("500")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("B9 — negative discount → BusinessRuleException")
    void b9_negativeDiscount_throws() {
        assertThatThrownBy(() -> calculator().discount(new BigDecimal("-1"), new BigDecimal("500")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("discount must not be negative");
    }

    @Test
    @DisplayName("B10 — discount greater than the subtotal → BusinessRuleException")
    void b10_discountAboveSubtotal_throws() {
        assertThatThrownBy(() -> calculator().discount(new BigDecimal("600"), new BigDecimal("500")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed the subtotal");
    }

    @Test
    @DisplayName("B11 — a discount equal to the subtotal is allowed (fully free)")
    void b11_discountEqualSubtotal_allowed() {
        assertThat(calculator().discount(new BigDecimal("500"), new BigDecimal("500")))
                .isEqualByComparingTo("500.00");
    }

    // ── Totals ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B12 — subtotal is normalised to money scale")
    void b12_subtotal_normalised() {
        assertThat(calculator().subtotal(new BigDecimal("100.005"))).isEqualByComparingTo("100.01");
    }

    @Test
    @DisplayName("B13 — taxable amount is subtotal minus discount")
    void b13_taxable_isSubtotalMinusDiscount() {
        assertThat(calculator().taxableAmount(new BigDecimal("1000.00"), new BigDecimal("100.00")))
                .isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("B14 — tax is 18% of the taxable amount")
    void b14_tax_isRateOfTaxable() {
        assertThat(calculator().tax(new BigDecimal("900.00"))).isEqualByComparingTo("162.00");
    }

    @Test
    @DisplayName("B15 — tax on a zero taxable amount is zero, not an exception")
    void b15_tax_onZero_isZero() {
        assertThat(calculator().tax(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("B16 — total is taxable plus tax")
    void b16_total_isTaxablePlusTax() {
        assertThat(calculator().total(new BigDecimal("900.00"), new BigDecimal("162.00")))
                .isEqualByComparingTo("1062.00");
    }

    @Test
    @DisplayName("B17 — full chain: 1000 − 100 discount → 900 taxable → 162 tax → 1062 total")
    void b17_fullChain() {
        BillingCalculator c = calculator();
        BigDecimal subtotal = c.subtotal(new BigDecimal("1000.00"));
        BigDecimal discount  = c.discount(new BigDecimal("100.00"), subtotal);
        BigDecimal taxable   = c.taxableAmount(subtotal, discount);
        BigDecimal tax       = c.tax(taxable);
        BigDecimal total     = c.total(taxable, tax);

        assertThat(subtotal).isEqualByComparingTo("1000.00");
        assertThat(discount).isEqualByComparingTo("100.00");
        assertThat(taxable).isEqualByComparingTo("900.00");
        assertThat(tax).isEqualByComparingTo("162.00");
        assertThat(total).isEqualByComparingTo("1062.00");
    }

    @Test
    @DisplayName("B18 — the configured tax rate is honoured (5% here)")
    void b18_configuredRate_honoured() {
        BillingCalculator fivePercent = new BillingCalculator(new BigDecimal("5"));
        assertThat(fivePercent.getTaxRatePercent()).isEqualByComparingTo("5");
        assertThat(fivePercent.tax(new BigDecimal("1000.00"))).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("B18b — a negative configured rate is rejected at construction")
    void b18b_negativeRate_throws() {
        assertThatThrownBy(() -> new BillingCalculator(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("B19 — money() rounds HALF_UP and never throws on null")
    void b19_money_roundsAndHandlesNull() {
        assertThat(BillingCalculator.money(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
        assertThat(BillingCalculator.money(null)).isEqualByComparingTo("0.00");
    }

    /* ================================================================
       Per-document GST rate (gstRate on the request)
       ================================================================ */

    @Test
    @DisplayName("GR1 — resolveRate(null) falls back to the configured default")
    void gr1_nullRate_usesConfiguredDefault() {
        assertThat(calculator().resolveRate(null)).isEqualByComparingTo("18");
    }

    @Test
    @DisplayName("GR2 — resolveRate accepts the rates the UI offers")
    void gr2_offeredRates_accepted() {
        BillingCalculator calc = calculator();
        assertThat(calc.resolveRate(new BigDecimal("18"))).isEqualByComparingTo("18");
        assertThat(calc.resolveRate(new BigDecimal("5"))).isEqualByComparingTo("5");
        assertThat(calc.resolveRate(new BigDecimal("12"))).isEqualByComparingTo("12");
        assertThat(calc.resolveRate(new BigDecimal("28"))).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("GR3 — tax at an explicit rate ignores the configured default")
    void gr3_tax_usesExplicitRate() {
        BillingCalculator calc = calculator(); // configured 18
        assertThat(calc.tax(new BigDecimal("1000.00"), new BigDecimal("5"))).isEqualByComparingTo("50.00");
        assertThat(calc.tax(new BigDecimal("1000.00"), new BigDecimal("12"))).isEqualByComparingTo("120.00");
        assertThat(calc.tax(new BigDecimal("1000.00"), new BigDecimal("28"))).isEqualByComparingTo("280.00");
        assertThat(calc.tax(new BigDecimal("1000.00"), new BigDecimal("0"))).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("GR4 — a 0% rate really does produce a zero-tax total")
    void gr4_zeroRate_zeroTax() {
        BillingCalculator calc = calculator();
        BigDecimal taxable = calc.taxableAmount(new BigDecimal("1000.00"), BigDecimal.ZERO);
        BigDecimal gst = calc.tax(taxable, calc.resolveRate(BigDecimal.ZERO));
        BigDecimal total = calc.total(taxable, gst);
        assertThat(gst).isEqualByComparingTo("0.00");
        assertThat(total).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("GR5 — a null explicit rate falls back to the default, never to zero")
    void gr5_nullExplicitRate_fallsBack() {
        BillingCalculator calc = calculator();
        assertThat(calc.tax(new BigDecimal("1000.00"), null)).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("GR6 — a negative rate is rejected")
    void gr6_negativeRate_throws() {
        assertThatThrownBy(() -> calculator().resolveRate(new BigDecimal("-1")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("gstRate must not be negative");
    }

    @Test
    @DisplayName("GR7 — a rate above 100 is rejected")
    void gr7_rateAboveHundred_throws() {
        assertThatThrownBy(() -> calculator().resolveRate(new BigDecimal("101")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("gstRate must not exceed 100");
    }

    @Test
    @DisplayName("GR8 — the full chain honours a per-document rate")
    void gr8_fullChain_honoursExplicitRate() {
        BillingCalculator calc = calculator();
        BigDecimal subtotal = calc.subtotal(new BigDecimal("2400.00"));
        BigDecimal discount = calc.discount(new BigDecimal("50"), subtotal);
        BigDecimal taxable = calc.taxableAmount(subtotal, discount);
        BigDecimal rate = calc.resolveRate(new BigDecimal("12"));
        BigDecimal tax = calc.tax(taxable, rate);
        BigDecimal total = calc.total(taxable, tax);
        assertThat(subtotal).isEqualByComparingTo("2400.00");
        assertThat(taxable).isEqualByComparingTo("2350.00");
        assertThat(rate).isEqualByComparingTo("12");
        assertThat(tax).isEqualByComparingTo("282.00");
        assertThat(total).isEqualByComparingTo("2632.00");
    }
}
