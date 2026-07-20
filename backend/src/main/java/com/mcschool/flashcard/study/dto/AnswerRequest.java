package com.mcschool.flashcard.study.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The student's chosen option for a card. {@code selectedAnswer} is the option text. */
public record AnswerRequest(
        @NotNull UUID cardId,
        @NotBlank String selectedAnswer
) {
}
