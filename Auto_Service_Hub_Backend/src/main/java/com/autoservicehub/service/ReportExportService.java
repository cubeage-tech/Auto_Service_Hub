package com.autoservicehub.service;

import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.ExportReportType;
import com.autoservicehub.dto.ReportFilterDTO;

/**
 * PDF / Excel export for the reports module (SRS FR-REP-8).
 *
 * <p>Export performs <em>no calculation of its own</em>. It calls the same
 * {@link ReportService} method the JSON endpoint calls, with the same
 * {@link ReportFilterDTO}, and formats the result. A figure in an export is
 * therefore by construction the figure the JSON report returns — they cannot
 * drift, because there is only one place that derives them.
 *
 * <p>An invalid range or an unusable filter surfaces exactly as it does on the
 * JSON endpoint, because the same service call raises it.
 */
public interface ReportExportService {

    /** Renders a report as a PDF document. */
    ExportFileDTO exportPdf(ExportReportType type, ReportFilterDTO filter);

    /** Renders a report as an Excel workbook. */
    ExportFileDTO exportExcel(ExportReportType type, ReportFilterDTO filter);
}
