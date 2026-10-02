package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Invoice count and value for one invoice status, FR-REP-4. */
@Getter
@Setter
public class InvoiceStatusSummaryDTO {

    private String status;

    private long invoiceCount;

    private BigDecimal total = BigDecimal.ZERO;
}
