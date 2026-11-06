package com.mcschool.flashcard.cards;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.cards.dto.CardResponse;
import com.mcschool.flashcard.cards.dto.CardSummaryResponse;
import com.mcschool.flashcard.cards.dto.CreateCardRequest;
import com.mcschool.flashcard.cards.dto.ImportCardsRequest;
import com.mcschool.flashcard.cards.dto.ImportPreviewRequest;
import com.mcschool.flashcard.cards.dto.ImportPreviewResponse;
import com.mcschool.flashcard.cards.dto.UpdateCardRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher-only flashcard management. All routes require the TEACHER role; the
 * service additionally enforces that the teacher owns the target student/card.
 */
@RestController
@PreAuthorize("hasRole('TEACHER')")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping("/api/v1/homeworks/{homeworkId}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse createCardInHomework(@AuthenticationPrincipal AuthenticatedUser caller,
                                             @PathVariable UUID homeworkId,
                                             @Valid @RequestBody CreateCardRequest request) {
        return cardService.createCardInHomework(caller, homeworkId, request);
    }

    @GetMapping("/api/v1/students/{studentId}/cards")
    public List<CardResponse> listCards(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable UUID studentId) {
        return cardService.listCards(caller, studentId);
    }

    @GetMapping("/api/v1/homeworks/{homeworkId}/cards")
    public List<CardResponse> listCardsForHomework(@AuthenticationPrincipal AuthenticatedUser caller,
                                                   @PathVariable UUID homeworkId) {
        return cardService.listCardsForHomework(caller, homeworkId);
    }

    @GetMapping("/api/v1/students/{studentId}/cards/summary")
    public CardSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable UUID studentId) {
        return cardService.summary(caller, studentId);
    }

    /** Preview parsed import text before saving. Stateless — nothing is persisted. */
    @PostMapping("/api/v1/cards/import/preview")
    public ImportPreviewResponse previewImport(@Valid @RequestBody ImportPreviewRequest request) {
        return cardService.previewImport(request);
    }

    @PostMapping("/api/v1/homeworks/{homeworkId}/cards/import")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CardResponse> importCardsIntoHomework(@AuthenticationPrincipal AuthenticatedUser caller,
                                                      @PathVariable UUID homeworkId,
                                                      @Valid @RequestBody ImportCardsRequest request) {
        return cardService.importCardsIntoHomework(caller, homeworkId, request);
    }

    @PutMapping("/api/v1/cards/{cardId}")
    public CardResponse updateCard(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable UUID cardId,
                                   @Valid @RequestBody UpdateCardRequest request) {
        return cardService.updateCard(caller, cardId, request);
    }

    @DeleteMapping("/api/v1/cards/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(@AuthenticationPrincipal AuthenticatedUser caller,
                           @PathVariable UUID cardId) {
        cardService.deleteCard(caller, cardId);
    }
}
