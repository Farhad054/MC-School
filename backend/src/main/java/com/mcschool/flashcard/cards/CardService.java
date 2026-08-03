package com.mcschool.flashcard.cards;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.cards.dto.CardResponse;
import com.mcschool.flashcard.cards.dto.CardSummaryResponse;
import com.mcschool.flashcard.cards.dto.CreateCardRequest;
import com.mcschool.flashcard.cards.dto.ImportCardsRequest;
import com.mcschool.flashcard.cards.dto.ImportPreviewRequest;
import com.mcschool.flashcard.cards.dto.ImportPreviewResponse;
import com.mcschool.flashcard.cards.dto.UpdateCardRequest;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher-facing flashcard management. Every operation is scoped to the calling
 * teacher: a teacher may only touch cards belonging to their own students, so a
 * teacher can never see or change another teacher's material (PRD key principles).
 */
@Service
public class CardService {

    private final CardRepository cardRepository;
    private final UserRepository userRepository;
    private final CardImportParser importParser;

    public CardService(CardRepository cardRepository, UserRepository userRepository,
                       CardImportParser importParser) {
        this.cardRepository = cardRepository;
        this.userRepository = userRepository;
        this.importParser = importParser;
    }

    // --- Import ---

    /** Stateless: parses pasted text into a preview without saving anything. */
    public ImportPreviewResponse previewImport(ImportPreviewRequest request) {
        return importParser.parse(request.rawText(), request.questionAnswerSeparator(),
                request.cardSeparator());
    }

    @Transactional
    public List<CardResponse> importCards(AuthenticatedUser teacher, UUID studentId,
                                          ImportCardsRequest request) {
        User teacherEntity = requireTeacher(teacher.id());
        User student = requireOwnedStudent(teacher.id(), studentId);
        return request.cards().stream()
                .map(parsed -> cardRepository.save(
                        Card.create(student, teacherEntity, parsed.question(), parsed.correctAnswer())))
                .map(CardResponse::from)
                .toList();
    }

    // --- CRUD ---

    @Transactional
    public CardResponse createCard(AuthenticatedUser teacher, UUID studentId, CreateCardRequest request) {
        User teacherEntity = requireTeacher(teacher.id());
        User student = requireOwnedStudent(teacher.id(), studentId);
        Card card = cardRepository.save(
                Card.create(student, teacherEntity, request.question(), request.correctAnswer()));
        return CardResponse.from(card);
    }

    @Transactional(readOnly = true)
    public List<CardResponse> listCards(AuthenticatedUser teacher, UUID studentId) {
        requireOwnedStudent(teacher.id(), studentId);
        return cardRepository.findAllByStudentIdAndArchivedFalseOrderByCreatedAtDesc(studentId).stream()
                .map(CardResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CardSummaryResponse summary(AuthenticatedUser teacher, UUID studentId) {
        requireOwnedStudent(teacher.id(), studentId);
        LocalDate today = LocalDate.now();
        long total = cardRepository.countByStudentIdAndArchivedFalse(studentId);
        long learned = cardRepository.countByStudentIdAndStatusAndArchivedFalse(studentId, CardStatus.LEARNED);
        long awaiting = cardRepository.countAwaitingRepetition(studentId, today);
        long dueNow = total - learned - awaiting;
        return new CardSummaryResponse(total, dueNow, awaiting, learned);
    }

    @Transactional
    public CardResponse updateCard(AuthenticatedUser teacher, UUID cardId, UpdateCardRequest request) {
        Card card = requireOwnedCard(teacher.id(), cardId);
        card.edit(request.question(), request.correctAnswer());
        return CardResponse.from(card);
    }

    /**
     * "Deletes" a card by archiving it. The row is kept so study-session history that
     * references it stays intact and no foreign-key violation can occur.
     */
    @Transactional
    public void deleteCard(AuthenticatedUser teacher, UUID cardId) {
        Card card = requireOwnedCard(teacher.id(), cardId);
        card.archive();
    }

    // --- Ownership helpers ---
    // "Not found" (rather than "forbidden") is returned when a student or card
    // exists but belongs to another teacher, so the API never confirms that
    // someone else's resource exists.

    private User requireTeacher(UUID teacherId) {
        return userRepository.findById(teacherId)
                .orElseThrow(() -> new ResourceNotFoundException("Teacher account no longer exists"));
    }

    private User requireOwnedStudent(UUID teacherId, UUID studentId) {
        User student = userRepository.findById(studentId)
                .filter(u -> u.getRole() == Role.STUDENT)
                .filter(u -> u.getTeacher() != null && u.getTeacher().getId().equals(teacherId))
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));
        return student;
    }

    private Card requireOwnedCard(UUID teacherId, UUID cardId) {
        return cardRepository.findById(cardId)
                .filter(card -> !card.isArchived())
                .filter(card -> card.getStudent().getTeacher() != null
                        && card.getStudent().getTeacher().getId().equals(teacherId))
                .orElseThrow(() -> new ResourceNotFoundException("Card not found"));
    }
}
