package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Inbound payload for recording notes against a task (FR-JOB-5).
 *
 * <p>Separate from {@link JobTaskRequestDTO} so a mechanic can add what they
 * found or did without restating the task's description, status or labour cost.
 * Updating a general task already permits changing notes; this endpoint exists
 * because "note-taking while working" is the common case and the narrow payload
 * makes it impossible to change a money field by accident.
 *
 * <p>{@code workNotes} is deliberately not {@code @NotBlank}: clearing the notes
 * is a legitimate action, and an empty string is stored as null rather than as a
 * blank note that reads as content.
 */
@Getter
@Setter
public class JobTaskWorkNotesRequestDTO {

    private String workNotes;
}