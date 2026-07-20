package com.mcschool.flashcard.cards;

import com.mcschool.flashcard.cards.dto.ImportPreviewResponse;
import com.mcschool.flashcard.cards.dto.ParsedCard;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses the "question → answer" text a teacher pastes from an external AI tool
 * (PRD 4.1, import). Splitting is literal (not regex) so separators like "-" or
 * "|" behave exactly as typed. Lines that cannot be split into a question and an
 * answer are skipped and reported as warnings rather than failing the whole import.
 */
@Component
public class CardImportParser {

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_ANSWER_LENGTH = 500;

    public ImportPreviewResponse parse(String rawText, String questionAnswerSeparator, String cardSeparator) {
        List<ParsedCard> cards = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String[] rawCards = splitLiterally(rawText, cardSeparator);
        for (String rawCard : rawCards) {
            String entry = rawCard.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int separatorIndex = entry.indexOf(questionAnswerSeparator);
            if (separatorIndex < 0) {
                warnings.add("Skipped (no answer separator found): " + preview(entry));
                continue;
            }
            String question = entry.substring(0, separatorIndex).strip();
            String answer = entry.substring(separatorIndex + questionAnswerSeparator.length()).strip();
            if (question.isEmpty() || answer.isEmpty()) {
                warnings.add("Skipped (empty question or answer): " + preview(entry));
                continue;
            }
            if (question.length() > MAX_QUESTION_LENGTH || answer.length() > MAX_ANSWER_LENGTH) {
                warnings.add("Skipped (question or answer too long): " + preview(entry));
                continue;
            }
            cards.add(new ParsedCard(question, answer));
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
