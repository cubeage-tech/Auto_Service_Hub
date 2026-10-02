package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Successful payments for one mode (CASH / CARD / UPI …), FR-REP-4. */
@Getter
@Setter
public class PaymentModeSummaryDTO {

    private String mode;

    private long paymentCount;

    private BigDecimal total = BigDecimal.ZERO;
}
