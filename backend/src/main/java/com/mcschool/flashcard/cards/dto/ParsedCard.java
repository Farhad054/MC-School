package com.mcschool.flashcard.cards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One question/answer pair, used both in the import preview and the confirmed import. */
public record ParsedCard(
        @NotBlank @Size(max = 1000) String question,
        @NotBlank @Size(max = 500) String correctAnswer
) {
}
