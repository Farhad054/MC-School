package com.mcschool.flashcard.common;

/**
 * Thrown when a request conflicts with existing state. Maps to 409.
 * A specific {@code errorCode} (e.g. {@code NOT_ENOUGH_CARDS}) lets clients
 * react to individual business rules without parsing the message text.
 */
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String message) {
        this("CONFLICT", message);
    }

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
