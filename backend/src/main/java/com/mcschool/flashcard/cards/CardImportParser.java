package com.mcschool.flashcard.cards;

import com.mcschool.flashcard.cards.dto.ImportPreviewResponse;
import com.mcschool.flashcard.cards.dto.ParsedCard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses imported homework cards. Each line is:
 * question -> correct answer | wrong answer 1 | wrong answer 2 | wrong answer 3.
 * Invalid lines are skipped and reported as warnings rather than failing the
 * whole import.
 */
@Component
public class CardImportParser {

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_ANSWER_LENGTH = 500;
    private static final String QUESTION_SEPARATOR = "->";
    private static final String ANSWER_SEPARATOR = "|";

    public ImportPreviewResponse parse(String rawText, String questionAnswerSeparator, String cardSeparator) {
        List<ParsedCard> cards = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String[] rawCards = splitLiterally(rawText, cardSeparator);
        for (int i = 0; i < rawCards.length; i++) {
            String entry = rawCards[i].strip();
            if (entry.isEmpty()) {
                continue;
            }
            int lineNumber = i + 1;
            int separatorIndex = entry.indexOf(QUESTION_SEPARATOR);
            if (separatorIndex < 0) {
                warnings.add("Line " + lineNumber + ": skipped (missing -> separator): " + preview(entry));
                continue;
            }
            String question = entry.substring(0, separatorIndex).strip();
            String answersText = entry.substring(separatorIndex + QUESTION_SEPARATOR.length()).strip();
            if (question.isEmpty()) {
                warnings.add("Line " + lineNumber + ": skipped (empty question): " + preview(entry));
                continue;
            }
            String[] rawAnswers = splitLiterally(answersText, ANSWER_SEPARATOR);
            if (rawAnswers.length != 4) {
                warnings.add("Line " + lineNumber + ": skipped (expected exactly 4 answers separated by |): "
                        + preview(entry));
                continue;
            }
            List<String> answers = new ArrayList<>();
            boolean hasBlankAnswer = false;
            for (String rawAnswer : rawAnswers) {
                String answer = rawAnswer.strip();
                if (answer.isEmpty()) {
                    hasBlankAnswer = true;
                }
                answers.add(answer);
            }
            if (hasBlankAnswer) {
                warnings.add("Line " + lineNumber + ": skipped (answers must be non-empty): " + preview(entry));
                continue;
            }
            Set<String> distinctAnswers = new LinkedHashSet<>(answers);
            if (distinctAnswers.size() != 4) {
                warnings.add("Line " + lineNumber + ": skipped (all 4 answers must be different): "
                        + preview(entry));
                continue;
            }
            boolean tooLong = question.length() > MAX_QUESTION_LENGTH
                    || answers.stream().anyMatch(a -> a.length() > MAX_ANSWER_LENGTH);
            if (tooLong) {
                warnings.add("Line " + lineNumber + ": skipped (question or answer too long): " + preview(entry));
                continue;
            }
            cards.add(new ParsedCard(question, answers.get(0), answers.get(1), answers.get(2), answers.get(3)));
        }
        return new ImportPreviewResponse(cards, warnings);
    }

    /** Literal (non-regex) split that keeps empty segments out. */
    private static String[] splitLiterally(String text, String separator) {
        return text.split(java.util.regex.Pattern.quote(separator), -1);
    }

    private static String preview(String entry) {
        return entry.length() <= 60 ? entry : entry.substring(0, 60) + "…";
    }
}
