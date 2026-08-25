package com.mcschool.flashcard.cards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import java.util.Set;

/** One question/answer pair, used both in the import preview and the confirmed import. */
public record ParsedCard(
        @NotBlank @Size(max = 1000) String question,
        @NotBlank @Size(max = 500) String correctAnswer,
        @NotBlank @Size(max = 500) String wrongAnswer1,
        @NotBlank @Size(max = 500) String wrongAnswer2,
        @NotBlank @Size(max = 500) String wrongAnswer3
) {
    @AssertTrue(message = "All answers must be distinct")
    public boolean isAnswerSetDistinct() {
        if (correctAnswer == null || wrongAnswer1 == null || wrongAnswer2 == null || wrongAnswer3 == null) {
            return true;
        }
        return Set.of(correctAnswer, wrongAnswer1, wrongAnswer2, wrongAnswer3).size() == 4;
    }
}
