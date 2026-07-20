package com.mcschool.flashcard.cards;

public enum CardStatus {
    /** Still being learned: appears in scheduled sessions when due. */
    ACTIVE,
    /** Successfully reviewed the required number of times; no longer scheduled. */
    LEARNED
}
