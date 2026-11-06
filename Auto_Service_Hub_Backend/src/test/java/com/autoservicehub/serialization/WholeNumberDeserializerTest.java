package com.autoservicehub.serialization;

import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.InvoiceItemRequestDTO;
import com.autoservicehub.exception.FractionalQuantityException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for fractional quantity binding.
 *
 * <p>The defect: {@code quantity} is an {@code Integer}, and Jackson's default
 * {@code ACCEPT_FLOAT_AS_INT} silently truncates — {@code 2.9} bound to 2. A
 * request for 2.9 units was invoiced for 2 and nothing in the response said so.
 * Verified against the shipped Jackson before this class was written:
 * {@code 1.5 -> 1}, {@code 2.9 -> 2}.
 *
 * <p>Q1  a whole number still binds
 * <p>Q2  a fractional number is rejected, naming the value
 * <p>Q3  trailing zeros are not fractional
 * <p>Q4  the same rule applies to estimates
 */
class WholeNumberDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Q1 — whole quantities are unchanged")
    void q1_wholeQuantitiesStillBind() throws Exception {
        assertThat(mapper.readValue("{\"quantity\":3}", InvoiceItemRequestDTO.class).getQuantity())
                .isEqualTo(3);
        assertThat(mapper.readValue("{\"quantity\":1}", InvoiceItemRequestDTO.class).getQuantity())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "quantity {0} is rejected, not truncated")
    @ValueSource(strings = { "2.9", "1.5", "0.1", "-3.5" })
    @DisplayName("Q2 — a fractional quantity is rejected rather than silently rounded")
    void q2_fractionalQuantityIsRejected(String json) {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":" + json + "}", InvoiceItemRequestDTO.class))
                .hasRootCauseInstanceOf(FractionalQuantityException.class)
                .rootCause()
                .hasMessageContaining("whole number")
                .hasMessageContaining("quantity");
    }

    @Test
    @DisplayName("Q3 — trailing zeros are precision, not a fraction, so 2.000 binds as 2")
    void q3_trailingZerosAreAccepted() throws Exception {
        assertThat(mapper.readValue("{\"quantity\":2.000}", InvoiceItemRequestDTO.class).getQuantity())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Q4 — estimate line items reject fractions on the same rule")
    void q4_estimateItemsRejectFractions() {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":2.9}", EstimateItemRequestDTO.class))
                .hasRootCauseInstanceOf(FractionalQuantityException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":\"2.9\"}", EstimateItemRequestDTO.class))
                .hasRootCauseInstanceOf(FractionalQuantityException.class);
    }

    @Test
    @DisplayName("Q5 — a numeric string of a whole number is still accepted")
    void q5_numericStringAccepted() throws Exception {
        assertThat(mapper.readValue("{\"quantity\":\"7\"}", InvoiceItemRequestDTO.class).getQuantity())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("Q6 — a non-numeric quantity is rejected, not coerced to zero")
    void q6_nonNumericRejected() {
        assertThatThrownBy(() -> mapper.readValue("{\"quantity\":\"two\"}", InvoiceItemRequestDTO.class))
                .hasRootCauseInstanceOf(FractionalQuantityException.class);
    }
}