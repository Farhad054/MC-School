package com.mcschool.flashcard.users.dto;

import com.mcschool.flashcard.users.Language;
import jakarta.validation.constraints.NotNull;

/** User-editable settings. Currently just the interface language (PRD settings screen). */
public record UpdateSettingsRequest(
        @NotNull Language preferredLanguage
) {
}
