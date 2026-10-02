package com.autoservicehub.service.impl;

import com.autoservicehub.dto.ExportDocumentDTO;
import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.ExportTableDTO;
import com.autoservicehub.dto.VehicleServiceHistoryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Renders a vehicle's service history as a downloadable document (FR-VEH-6,
 * "downloadable records").
 *
 * <p>Takes the already-assembled {@link VehicleServiceHistoryDTO} rather than
 * re-reading the database, so the document cannot disagree with the JSON history
 * endpoint — they are the same object rendered two ways.
 *
 * <p>Every value printed was computed by the service that owns it; nothing is
 * recomputed here.
 */
@Service
@RequiredArgsConstructor
public class VehicleHistoryDocumentService {

    private final ExportRenderer renderer;

    /** The history as a PDF, ready to stream. */
    public ExportFileDTO toPdf(VehicleServiceHistoryDTO history) {
        ExportDocumentDTO doc = build(history);
        return new ExportFileDTO(renderer.renderPdf(doc),
                ExportRenderer.CONTENT_TYPE_PDF,
                "vehicle-" + history.getVehicleId() + "-service-history.pdf");
    }

    private ExportDocumentDTO build(VehicleServiceHistoryDTO history) {
        ExportDocumentDTO doc = new ExportDocumentDTO(
                "Service History - Vehicle " + history.getVehicleId(),
                "FR-VEH-6",
                "vehicleId=" + history.getVehicleId(),
                LocalDateTime.now());

        doc.addTable(new ExportTableDTO("Vehicle", List.of("Field", "Value"))
                .addRow("Vehicle id", ExportRenderer.cell(history.getVehicleId()))
                .addRow("Vehicle", ExportRenderer.cell(history.getVehicleInfo()))
                .addRow("Total visits", ExportRenderer.cell(history.getTotalVisits()))
                .addRow("Total invoiced", ExportRenderer.cell(history.getTotalInvoiced())));

        for (VehicleServiceHistoryDTO.ServiceVisitDTO visit : history.getVisits()) {
            String label = visit.getJobCardNumber() == null
                    ? "Job " + visit.getJobCardId()
                    : visit.getJobCardNumber();

            doc.addTable(new ExportTableDTO(
                    "Visit: " + label, List.of("Field", "Value"))
                    .addRow("Job card id", ExportRenderer.cell(visit.getJobCardId()))
                    .addRow("Service type", ExportRenderer.cell(visit.getServiceType()))
                    .addRow("Status", ExportRenderer.cell(visit.getStatus()))
                    .addRow("Assigned", ExportRenderer.cell(visit.getAssignedDate()))
                    .addRow("Completed", ExportRenderer.cell(visit.getCompletedDate()))
                    .addRow("Invoice total", ExportRenderer.cell(visit.getInvoiceTotal())));

            ExportTableDTO tasks =
                    new ExportTableDTO("Tasks", List.of("Description", "Status", "Labour", "Mechanic"));
            for (VehicleServiceHistoryDTO.TaskDTO task : visit.getTasks()) {
                tasks.addRow(
                        ExportRenderer.cell(task.getDescription()),
                        ExportRenderer.cell(task.getStatus()),
                        ExportRenderer.cell(task.getLabourCost()),
                        ExportRenderer.cell(task.getMechanicName()));
            }
            doc.addTable(tasks);

            ExportTableDTO invoices =
                    new ExportTableDTO("Invoices", List.of("Invoice", "Status", "Total", "Date"));
            for (VehicleServiceHistoryDTO.InvoiceSummaryDTO invoice : visit.getInvoices()) {
                invoices.addRow(
                        ExportRenderer.cell(invoice.getId()),
                        ExportRenderer.cell(invoice.getStatus()),
                        ExportRenderer.cell(invoice.getTotal()),
                        ExportRenderer.cell(invoice.getInvoiceDate()));
            }
            doc.addTable(invoices);
        }

        if (history.getVisits().isEmpty()) {
            doc.addNote("This vehicle has no recorded service visits yet.");
        }
        doc.addNote("Figures are the values stored on the job cards, tasks and invoices "
                + "at the time of generation.");

        return doc;
    }
}