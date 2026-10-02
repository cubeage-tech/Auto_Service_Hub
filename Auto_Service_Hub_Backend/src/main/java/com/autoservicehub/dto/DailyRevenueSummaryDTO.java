package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's invoiced value, for the revenue trend, FR-REP-4. */
@Getter
@Setter
public class DailyRevenueSummaryDTO {

    private LocalDate date;

    private long invoiceCount;

    private BigDecimal total = BigDecimal.ZERO;
}
