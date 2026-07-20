package com.mcschool.flashcard.cards.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Confirmed import: the already-previewed list of cards to create for the student.
 * The client sends the reviewed pairs (not the raw text) so what is saved is
 * exactly what the teacher saw in the preview.
 */
public record ImportCardsRequest(
        @NotEmpty @Size(max = 500) @Valid List<ParsedCard> cards
) {
}
