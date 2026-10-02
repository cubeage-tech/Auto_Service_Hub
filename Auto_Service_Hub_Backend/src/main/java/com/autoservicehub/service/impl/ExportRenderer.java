package com.autoservicehub.service.impl;

import com.autoservicehub.dto.ExportDocumentDTO;
import com.autoservicehub.dto.ExportTableDTO;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders an {@link ExportDocumentDTO} to PDF or Excel.
 *
 * <p>Split out of {@link ReportExportServiceImpl} unchanged so the invoice
 * document required by FR-BILL-4 renders through exactly the same code as the
 * report exports of FR-REP-8. Sharing the renderer guarantees an invoice and a
 * report cannot disagree about how a null or an amount is displayed.
 *
 * <p>This class only formats. It never recomputes a total, a count or a rate —
 * every value it prints was produced upstream by the service that owns it.
 */
@Component
public class ExportRenderer {

    public static final String CONTENT_TYPE_PDF   = "application/pdf";
    public static final String CONTENT_TYPE_EXCEL =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** How a null is written. Blank rather than "null" — absence is not a value. */
    static final String BLANK = "";

    // OpenPDF dropped the iText 2.x style constants, so the equivalents are
    // spelled out here once rather than as magic numbers in the renderer.
    private static final int FONT_TITLE   = 15;
    private static final int FONT_SECTION = 11;
    private static final int FONT_BOLD    = 9;
    private static final int FONT_BODY    = 9;
    private static final int STYLE_NORMAL = Font.NORMAL;
    private static final int STYLE_BOLD   = Font.BOLD;
    private static final int STYLE_ITALIC = Font.ITALIC;

    /**
     * Renders the document with OpenPDF.
     *
     * <p>Helvetica is used rather than a system font: it is one of the fourteen
     * faces PDF requires every reader to supply, so the file renders identically
     * everywhere and needs no font embedding.
     */
    public byte[] renderPdf(ExportDocumentDTO doc) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document pdf = new Document(PageSize.A4, 36, 36, 42, 42);
            PdfWriter.getInstance(pdf, out);
            pdf.open();

            pdf.add(new Paragraph(doc.getTitle(), new Font(STYLE_BOLD, FONT_TITLE, 0)));
            pdf.add(new Paragraph("Requirement: " + doc.getRequirement(),
                    new Font(STYLE_NORMAL, FONT_BODY, 0)));
            pdf.add(new Paragraph("Generated: " + STAMP.format(doc.getGeneratedAt()),
                    new Font(STYLE_NORMAL, FONT_BODY, 0)));
            pdf.add(new Paragraph("Filters: " + doc.getAppliedFilters(), new Font(STYLE_NORMAL, FONT_BODY, 0)));
            pdf.add(Paragraph.getInstance("\n"));

            for (ExportTableDTO table : doc.getTables()) {
                pdf.add(new Paragraph(table.getTitle(), new Font(STYLE_BOLD, FONT_SECTION, 0)));
                if (table.isEmpty()) {
                    pdf.add(new Paragraph("No rows.", new Font(STYLE_ITALIC, FONT_BODY, 0)));
                    pdf.add(Paragraph.getInstance("\n"));
                    continue;
                }
                pdf.add(renderPdfTable(table));
                pdf.add(Paragraph.getInstance("\n"));
            }

            if (!doc.getNotes().isEmpty()) {
                pdf.add(new Paragraph("Notes", new Font(STYLE_BOLD, FONT_SECTION, 0)));
                for (String note : doc.getNotes()) {
                    // "-" plus a space: not all PDF standard fonts carry a bullet.
                    pdf.add(new Paragraph("- " + note, new Font(STYLE_NORMAL, FONT_BODY, 0)));
                }
            }

            pdf.close();
            return out.toByteArray();
        } catch (Exception ex) {
            // Rendering must never surface a raw IO stack trace to the client.
            throw new IllegalStateException("Failed to render PDF document", ex);
        }
    }

    /**
     * Builds the table body with {@link PdfPTable}, which supports percentage
     * widths and inherits from Element so it can be added to the Document.
     */
    private PdfPTable renderPdfTable(ExportTableDTO table) {
        int columns = table.getHeaders().size();
        PdfPTable pdfTable = new PdfPTable(columns);
        pdfTable.setWidthPercentage(100);
        pdfTable.setSpacingBefore(4);
        pdfTable.setSpacingAfter(4);
        pdfTable.setHorizontalAlignment(Element.ALIGN_LEFT);
        pdfTable.setWidths(columnWidths(columns));

        for (String header : table.getHeaders()) {
            pdfTable.addCell(new Phrase(header, new Font(STYLE_BOLD, FONT_BOLD, 0)));
        }
        for (List<String> row : table.getRows()) {
            for (String value : row) {
                pdfTable.addCell(new Phrase(value, new Font(STYLE_NORMAL, FONT_BODY, 0)));
            }
        }
        return pdfTable;
    }

    /** Even relative widths summing to 100; fixed widths would overflow the page. */
    private float[] columnWidths(int columns) {
        float[] widths = new float[columns];
        float each = 100f / columns;
        for (int i = 0; i < columns; i++) {
            widths[i] = each;
        }
        return widths;
    }

    /**
     * Renders the document as a single workbook with one sheet per table.
     *
     * <p>Numbers are written as typed cells, not text, so a user can sum a column
     * in Excel. Sheet names are sanitised and capped at Excel's 31-char limit.
     */
    public byte[] renderExcel(ExportDocumentDTO doc) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle metaStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font metaFont = workbook.createFont();
            metaFont.setBold(true);
            metaStyle.setFont(metaFont);

            Sheet info = workbook.createSheet(sheetName("Overview"));
            int r = 0;
            r = writeInfoRow(info, r, metaStyle, "Report", doc.getTitle());
            r = writeInfoRow(info, r, metaStyle, "Requirement", doc.getRequirement());
            r = writeInfoRow(info, r, metaStyle, "Generated", STAMP.format(doc.getGeneratedAt()));
            writeInfoRow(info, r, metaStyle, "Filters applied", doc.getAppliedFilters());

            int rowCursor = r + 1;
            if (!doc.getNotes().isEmpty()) {
                rowCursor = writeInfoRow(info, rowCursor, metaStyle, "Notes", null);
                for (String note : doc.getNotes()) {
                    rowCursor = writeInfoRow(info, rowCursor, metaStyle, null, note);
                }
            }
            info.setColumnWidth(0, 24 * 256);
            info.setColumnWidth(1, 100 * 256);

            for (ExportTableDTO table : doc.getTables()) {
                Sheet sheet = workbook.createSheet(sheetName(table.getTitle()));

                CellStyle headerStyle = workbook.createCellStyle();
                org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);

                Row header = sheet.createRow(0);
                for (int c = 0; c < table.getHeaders().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(table.getHeaders().get(c));
                    cell.setCellStyle(headerStyle);
                }

                int rowIndex = 1;
                for (List<String> row : table.getRows()) {
                    Row sheetRow = sheet.createRow(rowIndex++);
                    for (int c = 0; c < row.size(); c++) {
                        writeTyped(sheetRow, c, row.get(c));
                    }
                }
                for (int c = 0; c < table.getHeaders().size(); c++) {
                    sheet.setColumnWidth(c, 22 * 256);
                }
                if (table.isEmpty()) {
                    // An empty table still gets a visible, explicit marker row.
                    Row marker = sheet.createRow(1);
                    marker.createCell(0).setCellValue("No rows matched the applied filters.");
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render Excel document", ex);
        }
    }
    private int writeInfoRow(Sheet sheet, int rowIndex, CellStyle style, String label, String value) {
        Row row = sheet.createRow(rowIndex);
        if (label != null) {
            Cell labelCell = row.createCell(0);
            labelCell.setCellValue(label);
            labelCell.setCellStyle(style);
        }
        if (value != null) {
            row.createCell(1).setCellValue(value);
        }
        return rowIndex + 1;
    }

    /**
     * Writes a value as the type it actually is, so a spreadsheet can sum it.
     * Anything that is not a clean number falls back to text rather than being
     * silently corrupted into {@code 0}.
     */
    private void writeTyped(Row row, int column, String value) {
        Cell cell = row.createCell(column);
        if (value == null || value.isEmpty()) {
            cell.setBlank();
            return;
        }
        if (value.matches("-?\\d+")) {
            try {
                cell.setCellValue(Long.parseLong(value));
                return;
            } catch (NumberFormatException ignored) {
                // Falls through to text below; a too-large integer stays text.
            }
        }
        if (value.matches("-?\\d*\\.\\d+")) {
            try {
                cell.setCellValue(new BigDecimal(value).doubleValue());
                return;
            } catch (NumberFormatException ignored) {
                // Falls through to text below.
            }
        }
        cell.setCellValue(value);
    }

    /** Sheet names are capped at Excel's 31-character limit and cannot be blank. */
    private String sheetName(String title) {
        String name = title == null ? "Sheet" : title.replaceAll("[\\\\/*?\\[\\]:]", " ").trim();
        if (name.isEmpty()) {
            name = "Sheet";
        }
        return name.length() > 31 ? name.substring(0, 31) : name;
    }

    /**
     * Renders one value for display, identically in both formats.
     *
     * <p>A null becomes a blank cell rather than the text "null" or a zero: a
     * missing measurement is not a measurement of zero, and printing either would
     * turn "unknown" into a fact. This is the single place that decision is made,
     * so the PDF and the workbook cannot disagree about it.
     */
    public static String cell(Object value) {
        if (value == null) {
            return BLANK;
        }
        if (value instanceof BigDecimal decimal) {
            // Plain string, never scientific notation, so a large or
            // small-scale amount stays readable and matches the JSON report.
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}