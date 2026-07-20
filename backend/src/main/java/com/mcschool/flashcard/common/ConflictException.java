package com.mcschool.flashcard.common;

/** Thrown when a request conflicts with existing state (e.g. duplicate email). Maps to 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
