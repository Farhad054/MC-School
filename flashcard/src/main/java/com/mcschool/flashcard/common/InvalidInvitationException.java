package com.mcschool.flashcard.common;

/** Thrown when an invitation token is unknown, already used or expired. Maps to 400. */
public class InvalidInvitationException extends RuntimeException {

    public InvalidInvitationException(String message) {
        super(message);
    }
}
