package com.mcschool.flashcard.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcschool.flashcard.cards.Card;
import com.mcschool.flashcard.users.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class DistractorGeneratorTest {

    private final DistractorGenerator generator = new DistractorGenerator();
    private final User student = User.bootstrapAdmin("S", "s@test.local", "hash");
    private final User teacher = User.bootstrapAdmin("T", "t@test.local", "hash");

    private Card card(String answer) {
        return Card.create(student, teacher, "What is it?", answer);
    }

    @Test
    void buildsFourOptionsIncludingTheCorrectAnswer() {
        Card target = card("correct");
        List<Card> all = List.of(target, card("wrong1"), card("wrong2"), card("wrong3"), card("wrong4"));

        List<String> options = generator.buildOptions(target, all);

        assertThat(options).hasSize(4);
        assertThat(options).contains("correct");
        assertThat(options).doesNotHaveDuplicates();
    }

    @Test
    void usesAtMostThreeDistractors() {
        Card target = card("correct");
        List<Card> all = List.of(target,
                card("w1"), card("w2"), card("w3"), card("w4"), card("w5"), card("w6"));

        List<String> options = generator.buildOptions(target, all);

        assertThat(options).hasSize(4);
    }

    @Test
    void returnsFewerOptionsWhenNotEnoughDistinctDistractorsExist() {
        Card target = card("correct");
        List<Card> all = List.of(target, card("only-other"));

        List<String> options = generator.buildOptions(target, all);

        assertThat(options).containsExactlyInAnyOrder("correct", "only-other");
    }

    @Test
    void treatsDuplicateAnswersAsOneDistractor() {
        Card target = card("correct");
        List<Card> all = List.of(target, card("same"), card("same"), card("same"));

        List<String> options = generator.buildOptions(target, all);

        assertThat(options).containsExactlyInAnyOrder("correct", "same");
    }

    @Test
    void neverUsesTheCorrectAnswerAsADistractor() {
        Card target = card("42");
        List<Card> all = List.of(target, card("42"), card("7"), card("13"), card("99"));

        List<String> options = generator.buildOptions(target, all);

        assertThat(options).filteredOn(option -> option.equals("42")).hasSize(1);
    }
}
