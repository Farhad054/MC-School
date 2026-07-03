package com.mcschool.flashcard.common;

/** Thrown when a requested resource does not exist or the caller may not know it exists. Maps to 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
