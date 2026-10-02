package com.autoservicehub.dto;

/**
 * The reports that can be exported (FR-REP-8).
 *
 * <p>Each constant names a report the JSON API already serves, so an export can
 * never drift from the figures the same request returns as JSON — the export
 * reuses the same {@code ReportService} call.
 */
public enum ExportReportType {

    DAILY_WORKSHOP("daily-workshop", "Daily Workshop Report", "FR-REP-1"),
    REVENUE_PAYMENT("revenue-payment", "Revenue and Payments Report", "FR-REP-4"),
    MECHANIC_PERFORMANCE("mechanic-performance", "Mechanic Performance Report", "FR-REP-2"),
    PARTS_USAGE("parts-usage", "Parts Usage Report", "FR-REP-3"),
    CUSTOMER_GROWTH("customer-growth", "Customer Growth Report", "FR-REP-5"),
    PROFIT_ANALYSIS("profit-analysis", "Profit Analysis Report", "FR-REP-4");

    /** URL segment, e.g. {@code /api/v1/reports/export/daily-workshop.pdf}. */
    private final String slug;

    /** Human-readable heading written into the document. */
    private final String title;

    private final String requirement;

    ExportReportType(String slug, String title, String requirement) {
        this.slug = slug;
        this.title = title;
        this.requirement = requirement;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getRequirement() {
        return requirement;
    }

    /**
     * Resolves a URL segment to a report type.
     *
     * @return the matching type, or null when the segment names no export
     */
    public static ExportReportType fromSlug(String slug) {
        if (slug == null) {
            return null;
        }
        for (ExportReportType type : values()) {
            if (type.slug.equalsIgnoreCase(slug)) {
                return type;
            }
        }
        return null;
    }
}
