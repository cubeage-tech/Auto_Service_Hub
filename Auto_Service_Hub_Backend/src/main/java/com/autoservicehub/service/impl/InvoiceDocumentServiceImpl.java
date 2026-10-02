package com.autoservicehub.service.impl;

import com.autoservicehub.dto.ExportDocumentDTO;
import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.ExportTableDTO;
import com.autoservicehub.dto.InvoiceItemResponseDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.service.InvoiceDocumentService;
import com.autoservicehub.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds the printable invoice document required by FR-BILL-4.
 *
 * <p>The flow mirrors the report exports exactly:
 *
 * <pre>
 *   invoiceId -> InvoiceService.getById -> ExportDocumentDTO -> PDF | Excel
 * </pre>
 *
 * <p>Going back through {@link InvoiceService} rather than reading the repository
 * directly is deliberate: the document then inherits the same 404 for an unknown
 * invoice and the same field mapping the JSON endpoint uses, so there is exactly
 * one definition of what an invoice looks like.
 *
 * <p>Nothing monetary is recomputed. {@code subtotal}, {@code gst} and
 * {@code total} are printed exactly as {@link InvoiceServiceImpl} calculated and
 * stored them, and {@code amountPaid} / {@code outstandingAmount} as the service
 * derived them.
 */
@Service
@RequiredArgsConstructor
public class InvoiceDocumentServiceImpl implements InvoiceDocumentService {

    private final InvoiceService invoiceService;
    private final ExportRenderer renderer;

    @Override
    @Transactional(readOnly = true)
    public ExportFileDTO toPdf(Long invoiceId) {
        InvoiceResponseDTO invoice = invoiceService.getById(invoiceId);
        return new ExportFileDTO(renderer.renderPdf(build(invoice)),
                ExportRenderer.CONTENT_TYPE_PDF, filename(invoice, "pdf"));
    }

    @Override
    @Transactional(readOnly = true)
    public ExportFileDTO toExcel(Long invoiceId) {
        InvoiceResponseDTO invoice = invoiceService.getById(invoiceId);
        return new ExportFileDTO(renderer.renderExcel(build(invoice)),
                ExportRenderer.CONTENT_TYPE_EXCEL, filename(invoice, "xlsx"));
    }

    /** Reshapes the stored invoice into the shared, format-neutral export document. */
    private ExportDocumentDTO build(InvoiceResponseDTO invoice) {
        ExportDocumentDTO doc = new ExportDocumentDTO(
                "Invoice #" + invoice.getId(),
                "FR-BILL-4",
                "invoiceId=" + invoice.getId(),
                LocalDateTime.now());

        doc.addTable(new ExportTableDTO("Invoice", List.of("Field", "Value"))
                .addRow("Invoice number", ExportRenderer.cell(invoice.getId()))
                .addRow("Date", ExportRenderer.cell(invoice.getInvoiceDate()))
                .addRow("Status", ExportRenderer.cell(invoice.getStatus()))
                .addRow("Customer", ExportRenderer.cell(invoice.getCustomerName()))
                .addRow("Vehicle", ExportRenderer.cell(invoice.getVehicleInfo()))
                .addRow("Job card", ExportRenderer.cell(invoice.getJobCardId()))
                .addRow("Estimate", ExportRenderer.cell(invoice.getEstimateId())));

        ExportTableDTO lines = new ExportTableDTO(
                "Line items", List.of("#", "Description", "Category", "Qty", "Unit price", "Amount"));

        List<InvoiceItemResponseDTO> items =
                invoice.getItems() == null ? List.of() : invoice.getItems();
        int i = 1;
        for (InvoiceItemResponseDTO item : items) {
            lines.addRow(
                    ExportRenderer.cell(i++),
                    ExportRenderer.cell(item.getDescription()),
                    ExportRenderer.cell(item.getCategory()),
                    ExportRenderer.cell(item.getQuantity()),
                    ExportRenderer.cell(item.getUnitPrice()),
                    ExportRenderer.cell(item.getLineAmount()));
        }
        doc.addTable(lines);

        doc.addTable(new ExportTableDTO("Totals", List.of("Field", "Value"))
                .addRow("Subtotal", ExportRenderer.cell(invoice.getSubtotal()))
                .addRow("Discount", ExportRenderer.cell(invoice.getDiscount()))
                .addRow("GST", ExportRenderer.cell(invoice.getGst()))
                .addRow("Total", ExportRenderer.cell(invoice.getTotal()))
                .addRow("Amount paid", ExportRenderer.cell(invoice.getAmountPaid()))
                .addRow("Outstanding", ExportRenderer.cell(invoice.getOutstandingAmount())));

        doc.addNote("Amounts are as calculated and stored by the server at the time of "
                + "generation. This document is a record, not a live balance.");

        return doc;
    }

    /** e.g. {@code invoice-42-2026-03-10.pdf}. No path component, so it is download-safe. */
    private String filename(InvoiceResponseDTO invoice, String extension) {
        String date = invoice.getInvoiceDate() == null ? "" : "-" + invoice.getInvoiceDate();
        return "invoice-" + invoice.getId() + date + "." + extension;
    }
}