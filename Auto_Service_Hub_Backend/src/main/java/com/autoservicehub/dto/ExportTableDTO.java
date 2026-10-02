package com.autoservicehub.dto;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * One titled table of already-formatted cells inside an export.
 *
 * <p>This is deliberately format-neutral: both the PDF and the Excel writer
 * consume the same {@code ExportDocument}, so the two outputs cannot drift apart
 * or disagree with the JSON endpoint. Formatting (number rendering, null
 * handling) happens once, here, when the row is built.
 */
@Getter
public class ExportTableDTO {

    /** Section heading, e.g. "Status breakdown". */
    private final String title;

    private final List<String> headers;

    private final List<List<String>> rows = new ArrayList<>();

    public ExportTableDTO(String title, List<String> headers) {
        this.title = title;
        this.headers = headers;
    }

    public ExportTableDTO addRow(List<String> row) {
        rows.add(row);
        return this;
    }

    public ExportTableDTO addRow(String... cells) {
        return addRow(List.of(cells));
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
