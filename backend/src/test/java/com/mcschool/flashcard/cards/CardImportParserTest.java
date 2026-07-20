package com.mcschool.flashcard.cards;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcschool.flashcard.cards.dto.ImportPreviewResponse;
import org.junit.jupiter.api.Test;

class CardImportParserTest {

    private final CardImportParser parser = new CardImportParser();

    @Test
    void parsesCardsSeparatedByNewlinesAndArrow() {
        String text = "2 + 2 -> 4\n3 * 3 -> 9\nCapital of France -> Paris";

        ImportPreviewResponse result = parser.parse(text, "->", "\n");

        assertThat(result.cards()).hasSize(3);
        assertThat(result.cards().get(0).question()).isEqualTo("2 + 2");
        assertThat(result.cards().get(0).correctAnswer()).isEqualTo("4");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void trimsWhitespaceAroundQuestionsAndAnswers() {
        ImportPreviewResponse result = parser.parse("  x  ::  y  ", "::", "\n");

        assertThat(result.cards()).singleElement()
                .satisfies(card -> {
                    assertThat(card.question()).isEqualTo("x");
                    assertThat(card.correctAnswer()).isEqualTo("y");
                });
    }

    @Test
    void skipsLinesWithoutSeparatorAndReportsThem() {
        ImportPreviewResponse result = parser.parse("good = answer\nno separator here", "=", "\n");

        assertThat(result.cards()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("no answer separator");
    }

    @Test
    void skipsEmptyQuestionOrAnswer() {
        ImportPreviewResponse result = parser.parse(" = answer\nquestion = ", "=", "\n");

        assertThat(result.cards()).isEmpty();
        assertThat(result.warnings()).hasSize(2);
    }

    @Test
    void ignoresBlankCardsFromTrailingSeparators() {
        ImportPreviewResponse result = parser.parse("a - b;;c - d;", "-", ";");

        assertThat(result.cards()).hasSize(2);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void splitsOnlyOnTheFirstSeparatorOccurrence() {
        ImportPreviewResponse result = parser.parse("1 + 1 = 2 = maybe", "=", "\n");

        assertThat(result.cards()).singleElement()
                .satisfies(card -> {
                    assertThat(card.question()).isEqualTo("1 + 1");
                    assertThat(card.correctAnswer()).isEqualTo("2 = maybe");
                });
    }
}
