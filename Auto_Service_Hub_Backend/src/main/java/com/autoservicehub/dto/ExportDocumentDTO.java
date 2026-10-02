package com.autoservicehub.dto;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The complete, format-neutral content of one report export.
 *
 * <p>Produced once from a {@code ReportService} call and then rendered to PDF or
 * Excel. Building it in a single place is what makes the two formats
 * interchangeable: the same numbers, headings, filter echo and notes appear in
 * both, and both match the JSON endpoint because both come from the same service
 * result.
 */
@Getter
public class ExportDocumentDTO {

    /** Report heading, e.g. "Daily Workshop Report". */
    private final String title;

    /** The SRS requirement this report satisfies. */
    private final String requirement;

    /** Human-readable summary of the filters that were applied. */
    private final String appliedFilters;

    /** When the export was produced. */
    private final LocalDateTime generatedAt;

    /** Free-text notes: methodology, limitations, caveats. */
    private final List<String> notes = new ArrayList<>();

    private final List<ExportTableDTO> tables = new ArrayList<>();

    public ExportDocumentDTO(String title, String requirement,
                             String appliedFilters, LocalDateTime generatedAt) {
        this.title = title;
        this.requirement = requirement;
        this.appliedFilters = appliedFilters;
        this.generatedAt = generatedAt;
    }

    public ExportDocumentDTO addTable(ExportTableDTO table) {
        tables.add(table);
        return this;
    }

    public ExportDocumentDTO addNote(String note) {
        if (note != null && !note.isBlank()) {
            notes.add(note);
        }
        return this;
    }

    /** Total rows across every table, used for the empty-report check and tests. */
    public int totalRowCount() {
        // Each table is mapped to its row count first; mapToInt cannot chain
        // straight from the table to the nested list.
        return tables.stream()
                      .mapToInt(t -> t.getRows().size())
                      .sum();
    }
}
