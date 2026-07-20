package com.mcschool.flashcard.cards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Raw text pasted by the teacher plus the chosen separators (PRD 4.1, import).
 * The text is split into cards by {@code cardSeparator} and each card into a
 * question and an answer by {@code questionAnswerSeparator}.
 *
 * <p>The separators use {@code @NotEmpty} (not {@code @NotBlank}) because a
 * newline or tab is a perfectly valid separator even though it is whitespace.
 */
public record ImportPreviewRequest(
        @NotBlank @Size(max = 20_000) String rawText,
        @NotEmpty @Size(max = 10) String questionAnswerSeparator,
        @NotEmpty @Size(max = 10) String cardSeparator
) {
}
