package com.mcschool.flashcard.cards.dto;

import java.util.List;

/**
 * Result of parsing pasted import text. The teacher reviews {@code cards} (and any
 * {@code warnings} about lines that could not be parsed) before confirming the import.
 */
public record ImportPreviewResponse(
        List<ParsedCard> cards,
        List<String> warnings
) {
}
