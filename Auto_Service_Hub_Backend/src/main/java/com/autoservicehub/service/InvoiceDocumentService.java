package com.autoservicehub.service;

import com.autoservicehub.dto.ExportFileDTO;

/**
 * Printable / downloadable invoice documents (SRS FR-BILL-4:
 * "Generate printable/downloadable invoice documents").
 *
 * <p>Read-only. Every method re-reads the invoice through
 * {@link InvoiceService#getById(Long)}, so the document is built from exactly the
 * data the JSON endpoint would return and cannot disagree with it — including the
 * 404 for an unknown invoice, which is raised by that same call.
 *
 * <p>No total, tax figure or amount is recomputed here. The renderer is shared
 * with the FR-REP-8 report exports so an invoice and a report format values
 * identically.
 */
public interface InvoiceDocumentService {

    /** The invoice as a PDF. */
    ExportFileDTO toPdf(Long invoiceId);

    /** The invoice as an Excel workbook. */
    ExportFileDTO toExcel(Long invoiceId);
}