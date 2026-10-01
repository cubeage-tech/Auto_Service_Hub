package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class WorkNoteResponseDTO {
    private Long id;
    private Long jobCardId;
    private Long jobTaskId;
    private Long authorUserId;
    private String authorName;
    private Long mechanicId;
    private String content;
    private LocalDateTime createdAt;
}