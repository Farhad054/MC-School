package com.mcschool.flashcard.cards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Manual card creation: the teacher enters only the question and the correct answer. */
public record CreateCardRequest(
        @NotBlank @Size(max = 1000) String question,
        @NotBlank @Size(max = 500) String correctAnswer
) {
}
