package com.mcschool.flashcard.homeworks.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateHomeworkRequest(
        @NotNull LocalDate startDate
) {
}
