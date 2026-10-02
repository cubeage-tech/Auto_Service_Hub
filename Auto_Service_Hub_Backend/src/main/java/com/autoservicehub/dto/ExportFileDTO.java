package com.autoservicehub.dto;

import lombok.Getter;

/**
 * A rendered report file, ready to be streamed to the client (FR-REP-8).
 *
 * <p>Carries the bytes together with the metadata the HTTP layer needs, so the
 * controller does no formatting decisions of its own and cannot accidentally
 * send a {@code .xlsx} body under a {@code .pdf} content type.
 */
@Getter
public class ExportFileDTO {

    /** The rendered document. */
    private final byte[] content;

    /** e.g. {@code application/pdf} or the SpreadsheetML type. */
    private final String contentType;

    /** Suggested file name, without any directory component. */
    private final String filename;

    public ExportFileDTO(byte[] content, String contentType, String filename) {
        this.content = content;
        this.contentType = contentType;
        this.filename = filename;
    }

    public int size() {
        return content.length;
    }
}
