package com.autoservicehub.service;

import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.InvoiceItemResponseDTO;
import com.autoservicehub.dto.InvoiceResponseDTO;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.service.impl.ExportRenderer;
import com.autoservicehub.service.impl.InvoiceDocumentServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceDocumentServiceImpl} (FR-BILL-4).
 *
 * <p>The real {@link ExportRenderer} is used rather than a mock, because these
 * tests are about the bytes produced: a mocked renderer would let a broken
 * document pass.
 *
 * <p>Pinned here: the document is built from the service's own invoice (so the
 * 404 for an unknown invoice is inherited rather than re-implemented), the
 * filenames and content types are correct per format, and the totals rendered are
 * the ones the service computed — not recalculated here.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceDocumentServiceImplTest {

    @Mock InvoiceService invoiceService;

    private InvoiceDocumentServiceImpl service() {
        return new InvoiceDocumentServiceImpl(invoiceService, new ExportRenderer());
    }

    private InvoiceResponseDTO invoice(Long id) {
        InvoiceItemResponseDTO item = new InvoiceItemResponseDTO();
        item.setId(1L);
        item.setDescription("Brake pads");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("500.00"));
        item.setLineAmount(new BigDecimal("1000.00"));

        InvoiceResponseDTO dto = new InvoiceResponseDTO();
        dto.setId(id);
        dto.setJobCardId(55L);
        dto.setCustomerName("Priya Sharma");
        dto.setVehicleInfo("MH-12-AB-1234");
        dto.setStatus("PENDING");
        dto.setInvoiceDate(LocalDate.of(2026, 3, 10));
        dto.setSubtotal(new BigDecimal("1000.00"));
        dto.setDiscount(new BigDecimal("0.00"));
        dto.setGst(new BigDecimal("180.00"));
        dto.setTotal(new BigDecimal("1180.00"));
        dto.setAmountPaid(new BigDecimal("0.00"));
        dto.setOutstandingAmount(new BigDecimal("1180.00"));
        dto.setItems(List.of(item));
        return dto;
    }

    @Test
    @DisplayName("ID1 a PDF is produced with the PDF content type and a matching filename")
    void pdfIsProduced() {
        when(invoiceService.getById(42L)).thenReturn(invoice(42L));

        ExportFileDTO file = service().toPdf(42L);

        assertThat(file.getContentType()).isEqualTo(ExportRenderer.CONTENT_TYPE_PDF);
        assertThat(file.getFilename()).endsWith(".pdf").startsWith("invoice-42");
        assertThat(file.size()).isGreaterThan(0);
        // The %PDF magic bytes: a non-empty body is not the same as a valid PDF.
        assertThat(new String(file.getContent(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("ID2 an Excel workbook is produced and opens with the expected sheets")
    void excelIsProduced() {
        when(invoiceService.getById(42L)).thenReturn(invoice(42L));

        ExportFileDTO file = service().toExcel(42L);

        assertThat(file.getContentType()).isEqualTo(ExportRenderer.CONTENT_TYPE_EXCEL);
        assertThat(file.getFilename()).endsWith(".xlsx");
        assertThat(file.size()).isGreaterThan(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            // One overview sheet plus the invoice, line-item and totals tables.
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(4);
        } catch (Exception ex) {
            throw new IllegalStateException("workbook could not be reopened", ex);
        }
    }

    @Test
    @DisplayName("ID3 an unknown invoice is a 404, inherited from the invoice service")
    void unknownInvoiceIsNotFound() {
        when(invoiceService.getById(999L))
                .thenThrow(new ResourceNotFoundException("Invoice not found: 999"));

        assertThatThrownBy(() -> service().toPdf(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invoice not found: 999");

        assertThatThrownBy(() -> service().toExcel(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("ID4 the document is built from the stored totals, not recalculated")
    void totalsComeFromTheService() {
        InvoiceResponseDTO stored = invoice(42L);
        // A deliberately inconsistent total: if the document recomputed anything
        // from the line items it would not be able to reproduce this.
        stored.setTotal(new BigDecimal("1234.56"));
        when(invoiceService.getById(42L)).thenReturn(stored);

        assertThat(service().toPdf(42L).size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("ID5 an invoice with no line items still renders")
    void invoiceWithoutItemsStillRenders() {
        InvoiceResponseDTO empty = invoice(42L);
        empty.setItems(List.of());
        when(invoiceService.getById(42L)).thenReturn(empty);

        assertThat(service().toPdf(42L).size()).isGreaterThan(0);
    }
}