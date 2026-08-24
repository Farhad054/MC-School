package com.mcschool.flashcard.students.dto;

import com.mcschool.flashcard.cards.Card;
import java.time.LocalDate;
import java.util.UUID;

public record PilotDueCardResponse(
        UUID id,
        String question,
        LocalDate dueDate
) {

    public static PilotDueCardResponse from(Card card) {
        return new PilotDueCardResponse(card.getId(), card.getQuestion(), card.getDueDate());
    }
}
